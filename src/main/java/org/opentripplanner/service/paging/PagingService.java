package org.opentripplanner.service.paging;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;
import org.opentripplanner.framework.tostring.ToStringBuilder;
import org.opentripplanner.model.plan.Itinerary;
import org.opentripplanner.model.plan.SortOrder;
import org.opentripplanner.model.plan.paging.PagingSearchWindowAdjuster;
import org.opentripplanner.model.plan.paging.cursor.PageCursor;
import org.opentripplanner.model.plan.paging.cursor.PageCursorFactory;
import org.opentripplanner.model.plan.paging.cursor.PageType;
import org.opentripplanner.routing.algorithm.filterchain.deletionflagger.NumItinerariesFilterResults;
import org.opentripplanner.routing.api.response.TripSearchMetadata;

public class PagingService {

  private final Duration searchWindowUsed;
  private final Instant earliestDepartureTime;
  private final Instant latestArrivalTime;
  private final SortOrder itinerariesSortOrder;
  private final boolean arriveBy;
  private final int numberOfItineraries;
  private final PageCursor pageCursor;
  private final NumItinerariesFilterResults numItinerariesFilterResults;
  private final PagingSearchWindowAdjuster searchWindowAdjuster;
  private final List<Itinerary> itineraries;

  // Lazy init
  private PageCursorFactory pageCursorFactory = null;

  public PagingService(
    List<Duration> pagingSearchWindowAdjustments,
    Duration minSearchWindowSize,
    Duration maxSearchWindowSize,
    @Nullable Duration searchWindowUsed,
    @Nullable Instant earliestDepartureTime,
    @Nullable Instant latestArrivalTime,
    SortOrder itinerariesSortOrder,
    boolean arriveBy,
    int numberOfItineraries,
    @Nullable PageCursor pageCursor,
    NumItinerariesFilterResults numItinerariesFilterResults,
    List<Itinerary> itineraries
  ) {
    this.searchWindowUsed = searchWindowUsed;
    // EDT is required if search-window is set
    this.earliestDepartureTime =
      searchWindowUsed == null
        ? earliestDepartureTime
        : Objects.requireNonNull(earliestDepartureTime);
    this.latestArrivalTime = latestArrivalTime;
    this.itinerariesSortOrder = Objects.requireNonNull(itinerariesSortOrder);
    this.arriveBy = arriveBy;
    this.numberOfItineraries = numberOfItineraries;
    this.pageCursor = pageCursor;

    this.numItinerariesFilterResults = numItinerariesFilterResults;
    this.itineraries = itineraries;
    this.searchWindowAdjuster =
      createSearchWindowAdjuster(
        pagingSearchWindowAdjustments,
        minSearchWindowSize,
        maxSearchWindowSize
      );
  }

  public PageCursor nextPageCursor() {
    return pageCursorFactory().nextPageCursor();
  }

  public PageCursor previousPageCursor() {
    return pageCursorFactory().previousPageCursor();
  }

  @Nullable
  public TripSearchMetadata createTripSearchMetadata() {
    if (noTransitSearchPerformed()) {
      return null;
    }

    if (arriveBy) {
      return TripSearchMetadata.createForArriveBy(
        earliestDepartureTime,
        searchWindowUsed,
        firstKeptDepartureTime()
      );
    } else {
      return TripSearchMetadata.createForDepartAfter(
        earliestDepartureTime,
        searchWindowUsed,
        lastKeptDepartureTime()
      );
    }
  }

  private Duration calculateSearchWindowNextSearch() {
    if (noTransitSearchPerformed()) {
      return null;
    }

    // SearchWindow cropped -> decrease search-window
    if (numItinerariesFilterResults != null) {
      boolean cropSWHead = doCropSearchWindowAtTail();
      Instant rmItineraryStartTime = numItinerariesFilterResults.pageCut().startTimeAsInstant();

      return searchWindowAdjuster.decreaseSearchWindow(
        searchWindowUsed,
        earliestDepartureTime,
        rmItineraryStartTime,
        cropSWHead
      );
    }
    // (num-of-itineraries found <= numItineraries)  ->  increase or keep search-window
    else {
      int nFound = (int) itineraries
        .stream()
        .filter(it -> !it.isFlaggedForDeletion() && it.hasTransit())
        .count();

      return searchWindowAdjuster.increaseOrKeepSearchWindow(
        searchWindowUsed,
        numberOfItineraries,
        nFound
      );
    }
  }

  private Instant lastKeptDepartureTime() {
    return numItinerariesFilterResults == null
      ? null
      : numItinerariesFilterResults.pageCut().startTimeAsInstant();
  }

  private Instant firstKeptDepartureTime() {
    return numItinerariesFilterResults == null
      ? null
      : numItinerariesFilterResults.pageCut().startTimeAsInstant();
  }

  private PagingSearchWindowAdjuster createSearchWindowAdjuster(
    List<Duration> pagingSearchWindowAdjustments,
    Duration minSearchWindowSize,
    Duration maxSearchWindowSize
  ) {
    return new PagingSearchWindowAdjuster(
      minSearchWindowSize,
      maxSearchWindowSize,
      pagingSearchWindowAdjustments
    );
  }

  /**
   * Related to {@link org.opentripplanner.routing.api.request.RouteRequest#cropItinerariesAt()},
   * but is {@code true} if we should crop the search-window head(in the beginning) or tail(in the
   * end).
   * <p>
   * For the first search we look if the sort is ascending(crop tail) or descending(crop head), and
   * for paged results we look at the paging type: next(tail) and previous(head).
   */
  private boolean doCropSearchWindowAtTail() {
    if (pageCursor == null) {
      return itinerariesSortOrder.isSortedByAscendingArrivalTime();
    }
    return pageCursor.type().isNext();
  }

  private PageCursorFactory pageCursorFactory() {
    if (pageCursorFactory == null) {
      this.pageCursorFactory =
        mapIntoPageCursorFactory(pageCursor == null ? null : pageCursor.type());
    }
    return pageCursorFactory;
  }

  private PageCursorFactory mapIntoPageCursorFactory(@Nullable PageType currentPageType) {
    var searchWindowNextSearch = calculateSearchWindowNextSearch();
    var factory = new PageCursorFactory(itinerariesSortOrder, searchWindowNextSearch);

    if (noTransitSearchPerformed()) {
      return factory;
    }

    assertRequestPrerequisites();

    factory =
      factory.withOriginalSearch(
        currentPageType,
        earliestDepartureTime,
        latestArrivalTime,
        searchWindowUsed
      );

    if (numItinerariesFilterResults != null) {
      factory = factory.withRemovedItineraries(numItinerariesFilterResults);
    }
    return factory;
  }

  private void assertRequestPrerequisites() {
    if (noTransitSearchPerformed()) {
      throw new IllegalStateException("SearchWindow not set");
    }
    if (earliestDepartureTime == null) {
      throw new IllegalStateException("Earliest departure time not set");
    }
  }

  private boolean noTransitSearchPerformed() {
    return searchWindowUsed == null;
  }

  @Override
  public String toString() {
    return ToStringBuilder
      .of(PagingService.class)
      .addDuration("searchWindowUsed", searchWindowUsed)
      .addDateTime("earliestDepartureTime", earliestDepartureTime)
      .addDateTime("latestArrivalTime", latestArrivalTime)
      .addEnum("itinerariesSortOrder", itinerariesSortOrder)
      .addBoolIfTrue("arriveBy", arriveBy)
      .addNum("numberOfItineraries", numberOfItineraries)
      .addObj("pageCursor", pageCursor)
      .toString();
  }
}
