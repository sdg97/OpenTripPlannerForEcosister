package org.opentripplanner.routing.algorithm.filterchain;

import static org.opentripplanner.routing.algorithm.filterchain.comparator.SortOrderComparator.generalizedCostComparator;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.annotation.Nullable;
import org.opentripplanner.ext.accessibilityscore.AccessibilityScoreFilter;
import org.opentripplanner.framework.collection.ListSection;
import org.opentripplanner.framework.lang.Sandbox;
import org.opentripplanner.model.plan.Itinerary;
import org.opentripplanner.model.plan.ItinerarySortKey;
import org.opentripplanner.model.plan.SortOrder;
import org.opentripplanner.routing.algorithm.filterchain.api.TransitGeneralizedCostFilterParams;
import org.opentripplanner.routing.algorithm.filterchain.comparator.SortOrderComparator;
import org.opentripplanner.routing.algorithm.filterchain.deletionflagger.ItineraryDeletionFlagger;
import org.opentripplanner.routing.algorithm.filterchain.deletionflagger.MaxLimitFilter;
import org.opentripplanner.routing.algorithm.filterchain.deletionflagger.NonTransitGeneralizedCostFilter;
import org.opentripplanner.routing.algorithm.filterchain.deletionflagger.NumItinerariesFilter;
import org.opentripplanner.routing.algorithm.filterchain.deletionflagger.NumItinerariesFilterResults;
import org.opentripplanner.routing.algorithm.filterchain.deletionflagger.OtherThanSameLegsMaxGeneralizedCostFilter;
import org.opentripplanner.routing.algorithm.filterchain.deletionflagger.OutsideSearchWindowFilter;
import org.opentripplanner.routing.algorithm.filterchain.deletionflagger.PagingFilter;
import org.opentripplanner.routing.algorithm.filterchain.deletionflagger.RemoveBikerentalWithMostlyWalkingFilter;
import org.opentripplanner.routing.algorithm.filterchain.deletionflagger.RemoveItinerariesWithShortStreetLeg;
import org.opentripplanner.routing.algorithm.filterchain.deletionflagger.RemoveParkAndRideWithMostlyWalkingFilter;
import org.opentripplanner.routing.algorithm.filterchain.deletionflagger.RemoveTransitIfStreetOnlyIsBetterFilter;
import org.opentripplanner.routing.algorithm.filterchain.deletionflagger.RemoveTransitIfWalkingIsBetterFilter;
import org.opentripplanner.routing.algorithm.filterchain.deletionflagger.RemoveWalkOnlyFilter;
import org.opentripplanner.routing.algorithm.filterchain.deletionflagger.TransitGeneralizedCostFilter;
import org.opentripplanner.routing.algorithm.filterchain.filter.DeletionFlaggingFilter;
import org.opentripplanner.routing.algorithm.filterchain.filter.GroupByFilter;
import org.opentripplanner.routing.algorithm.filterchain.filter.RemoveDeletionFlagForLeastTransfersItinerary;
import org.opentripplanner.routing.algorithm.filterchain.filter.SameFirstOrLastTripFilter;
import org.opentripplanner.routing.algorithm.filterchain.filter.SortingFilter;
import org.opentripplanner.routing.algorithm.filterchain.filter.TransitAlertFilter;
import org.opentripplanner.routing.algorithm.filterchain.groupids.GroupByAllSameStations;
import org.opentripplanner.routing.algorithm.filterchain.groupids.GroupByDistance;
import org.opentripplanner.routing.algorithm.filterchain.groupids.GroupBySameRoutesAndStops;
import org.opentripplanner.routing.api.request.framework.CostLinearFunction;
import org.opentripplanner.routing.api.request.preference.ItineraryFilterDebugProfile;
import org.opentripplanner.routing.services.TransitAlertService;
import org.opentripplanner.street.search.TraverseMode;
import org.opentripplanner.transit.model.site.MultiModalStation;
import org.opentripplanner.transit.model.site.Station;

/**
 * Create a filter chain based on the given config.
 */
public class ItineraryListFilterChainBuilder {

  private static final int NOT_SET = -1;
  private final SortOrder sortOrder;
  private final List<GroupBySimilarity> groupBySimilarity = new ArrayList<>();

  private ItineraryFilterDebugProfile debug = ItineraryFilterDebugProfile.OFF;
  private int maxNumberOfItineraries = NOT_SET;
  private ListSection maxNumberOfItinerariesCropSection = ListSection.TAIL;
  private CostLinearFunction removeTransitWithHigherCostThanBestOnStreetOnly;
  private boolean removeWalkAllTheWayResults;
  private boolean sameFirstOrLastTripFilter;
  private TransitGeneralizedCostFilterParams transitGeneralizedCostFilterParams;
  private double bikeRentalDistanceRatio;
  private double parkAndRideDurationRatio;
  private CostLinearFunction nonTransitGeneralizedCostLimit;
  private Consumer<NumItinerariesFilterResults> numItinerariesFilterResultsConsumer;
  private Instant earliestDepartureTime = null;
  private Duration searchWindow = null;
  private boolean accessibilityScore;
  private double wheelchairMaxSlope;
  private TransitAlertService transitAlertService;
  private Function<Station, MultiModalStation> getMultiModalStation;
  private boolean removeItinerariesWithSameRoutesAndStops;
  private double minBikeParkingDistance;
  private boolean removeTransitIfWalkingIsBetter = true;
  private ItinerarySortKey itineraryPageCut;

  /**
   * Sandbox filters which decorate the itineraries with extra information.
   */

  @Sandbox
  private ItineraryListFilter emissionsFilter;

  @Sandbox
  private ItineraryListFilter faresFilter;

  @Sandbox
  private ItineraryListFilter rideHailingFilter;

  @Sandbox
  private ItineraryListFilter stopConsolidationFilter;

  public ItineraryListFilterChainBuilder(SortOrder sortOrder) {
    this.sortOrder = sortOrder;
  }

  /**
   * The maximum number of itineraries returned. This will remove all itineraries at the end of the
   * list AFTER the final sort of the itineraries.
   * <p>
   * Se also the {@link #withMaxNumberOfItinerariesCropSection(ListSection)} to change which end of the
   * list is cropped.
   * <p>
   * Use {@code -1} to disable.
   */
  public ItineraryListFilterChainBuilder withMaxNumberOfItineraries(int value) {
    this.maxNumberOfItineraries = value;
    return this;
  }

  /**
   * Remove itineraries from the tail or head of the list in the final filtering. The
   * {@link #maxNumberOfItineraries} is used together with this parameter to reduce the number of
   * itineraries down to the requested size.
   * <p>
   * The default is to crop the tail. But, we need to crop the head to be able to paginate in the
   * opposite direction of the main sort-order of the original search.
   */
  public ItineraryListFilterChainBuilder withMaxNumberOfItinerariesCropSection(
    ListSection section
  ) {
    this.maxNumberOfItinerariesCropSection = section;
    return this;
  }

  /**
   * Group itineraries by the main legs and keeping approximately the given total number of
   * itineraries. The itineraries are grouped by the legs that account for more than 'p' % for the
   * total distance.
   *
   * @see GroupBySimilarity for more details.
   */
  public ItineraryListFilterChainBuilder addGroupBySimilarity(GroupBySimilarity groupBySimilarity) {
    this.groupBySimilarity.add(groupBySimilarity);
    return this;
  }

  /**
   * The given parameters is used to compare all itineraries with each other, dropping itineraries
   * with a high relative cost. The limit is applied to itineraries with at least one transit
   * leg. Street-only itineraries are not considered.
   * <p>
   * For all pairs {code (i1, i2)} in the set of returned itineraries, a generalized cost limit is
   * computed. If the generalized-cost of i1 is less then the generalized-cost of i2, i2 is dropped if:
   * <pre>
   * t0 := is the time between i1 and i2.
   *
   * limit = i1.generalized-cost * costFactor + minimumCostDifference + t0 * itineraryIntervalRelaxFactor
   *
   * i2 is dropped if i2.generalized-cost is greater than the limit.
   * </pre>
   * @param transitGeneralizedCostFilterParams container for costFactor, minimumCostDifference and
   *                                           itineraryIntervalRelaxFactor
   */
  public ItineraryListFilterChainBuilder withTransitGeneralizedCostLimit(
    TransitGeneralizedCostFilterParams transitGeneralizedCostFilterParams
  ) {
    this.transitGeneralizedCostFilterParams = transitGeneralizedCostFilterParams;
    return this;
  }

  /**
   * This is a bit similar to {@link #withTransitGeneralizedCostLimit(TransitGeneralizedCostFilterParams)},
   * with a few important differences.
   * <p>
   * This function is used to compute a max-limit for generalized-cost. The limit is applied to
   * itineraries with no transit legs, however ALL itineraries (including those with transit legs)
   * are considered when calculating the minimum cost.
   * <p>
   * The smallest generalized-cost value is used as input to the function. For example if the
   * function is {@code f(x) = 1800 + 2.0 x} and the smallest cost is {@code 5000}, then all
   * non-transit itineraries with a cost larger than {@code 1800 + 2 * 5000 = 11 800} is dropped.
   */
  public ItineraryListFilterChainBuilder withNonTransitGeneralizedCostLimit(
    CostLinearFunction value
  ) {
    this.nonTransitGeneralizedCostLimit = value;
    return this;
  }

  /**
   * This is used to filter out bike rental itineraries that contain mostly walking. The value
   * describes the ratio of the total itinerary that has to consist of bike rental to allow the
   * itinerary.
   */
  public ItineraryListFilterChainBuilder withBikeRentalDistanceRatio(double value) {
    this.bikeRentalDistanceRatio = value;
    return this;
  }

  /**
   * This is used to filter out park and ride itineraries that contain only driving and a very long
   * walking leg. The value describes the amount of driving vs. walking to allow the itinerary.
   */
  public ItineraryListFilterChainBuilder withParkAndRideDurationRatio(double value) {
    this.parkAndRideDurationRatio = value;
    return this;
  }

  /**
   * The direct street search(walk, bicycle, car) is not pruning the transit search, so in some
   * cases we get "silly" transit itineraries that is marginally better on travel-duration compared
   * with an on-street-all-the-way itinerary. Use this method to filter worse enough itineraries.
   * <p>
   * The filter removes all itineraries with a generalized-cost that is higher than the best
   * on-street-all-the-way itinerary.
   * <p>
   * This filter only have an effect, if an on-street-all-the-way(WALK, BICYCLE, CAR) itinerary
   * exist.
   */
  public ItineraryListFilterChainBuilder withRemoveTransitWithHigherCostThanBestOnStreetOnly(
    CostLinearFunction value
  ) {
    this.removeTransitWithHigherCostThanBestOnStreetOnly = value;
    return this;
  }

  /**
   * A transit itinerary with higher generalized-cost than a walk-only itinerary is silly. This filter removes such
   * itineraries.
   * <p>
   * This filter only have an effect, if a walk-all-the-way itinerary exist.
   */
  public ItineraryListFilterChainBuilder withRemoveTransitIfWalkingIsBetter(boolean value) {
    this.removeTransitIfWalkingIsBetter = value;
    return this;
  }

  /**
   * This will NOT delete itineraries, but tag them as deleted using the {@link
   * Itinerary#getSystemNotices()}.
   */
  public ItineraryListFilterChainBuilder withDebugEnabled(ItineraryFilterDebugProfile value) {
    this.debug = value;
    return this;
  }

  /**
   * Set the search window for the current request. This is used to filter out itineraries outside
   * the search window. The filter uses the itinerary-departure-time. The filter is ignored if
   * both arguments are {@code null}, the searchWindow is required if the earliestDepartureTime is
   * set.
   */
  public ItineraryListFilterChainBuilder withSearchWindow(
    @Nullable Instant earliestDepartureTime,
    Duration searchWindow
  ) {
    if (earliestDepartureTime != null) {
      Objects.requireNonNull(searchWindow);
    }
    this.earliestDepartureTime = earliestDepartureTime;
    this.searchWindow = searchWindow;
    return this;
  }

  /**
   * If the maximum number of itineraries is exceeded, then the excess itineraries are removed. To
   * get notified about this a consumer can be added. The 'maxLimit' check is the last thing
   * happening in the filter-chain after the final sort. So, if another filter removes an itinerary,
   * the itinerary is not considered with respect to the {@link #withMaxNumberOfItineraries(int)}
   * limit.
   *
   * @param numItinerariesFilterResultsConsumer the consumer to notify when elements are removed.
   */
  public ItineraryListFilterChainBuilder withNumItinerariesFilterResultsConsumer(
    Consumer<NumItinerariesFilterResults> numItinerariesFilterResultsConsumer
  ) {
    this.numItinerariesFilterResultsConsumer = numItinerariesFilterResultsConsumer;
    return this;
  }

  /**
   * If the search is done with a page cursor that contains encoded deduplication parameters, then
   * this function adds the filter that removes duplicates.
   *
   * @param itineraryPageCut contains the parameters to use for deduplication.
   */
  public ItineraryListFilterChainBuilder withPagingDeduplicationFilter(
    ItinerarySortKey itineraryPageCut
  ) {
    this.itineraryPageCut = itineraryPageCut;
    return this;
  }

  /**
   * If set, walk-all-the-way itineraries are removed. This happens AFTER e.g. the group-by and
   * remove-transit-with-higher-cost-than-best-on-street-only filter. This make sure that poor
   * transit itineraries are filtered away before the walk-all-the-way itinerary is removed.
   */
  public ItineraryListFilterChainBuilder withRemoveWalkAllTheWayResults(boolean enable) {
    this.removeWalkAllTheWayResults = enable;
    return this;
  }

  public ItineraryListFilterChainBuilder withSameFirstOrLastTripFilter(boolean enable) {
    this.sameFirstOrLastTripFilter = enable;
    return this;
  }

  /**
   * Enable the IBI feature for calculating a very simple numeric accessibility score between 0 and
   * 1 for each leg in the itinerary.
   */
  public ItineraryListFilterChainBuilder withAccessibilityScore(
    boolean enable,
    double wheelchairMaxSlope
  ) {
    this.accessibilityScore = enable;
    this.wheelchairMaxSlope = wheelchairMaxSlope;
    return this;
  }

  public ItineraryListFilterChainBuilder withFaresFilter(ItineraryListFilter filter) {
    this.faresFilter = filter;
    return this;
  }

  public ItineraryListFilterChainBuilder withEmissions(ItineraryListFilter emissionsFilter) {
    this.emissionsFilter = emissionsFilter;
    return this;
  }

  public ItineraryListFilterChainBuilder withMinBikeParkingDistance(double distance) {
    this.minBikeParkingDistance = distance;
    return this;
  }

  public ItineraryListFilterChainBuilder withRemoveTimeshiftedItinerariesWithSameRoutesAndStops(
    boolean remove
  ) {
    this.removeItinerariesWithSameRoutesAndStops = remove;
    return this;
  }

  public ItineraryListFilterChainBuilder withRideHailingFilter(ItineraryListFilter filter) {
    this.rideHailingFilter = filter;
    return this;
  }

  public ItineraryListFilterChainBuilder withStopConsolidationFilter(
    @Nullable ItineraryListFilter filter
  ) {
    this.stopConsolidationFilter = filter;
    return this;
  }

  public ItineraryListFilterChain build() {
    List<ItineraryListFilter> filters = new ArrayList<>();

    filters.addAll(buildGroupByTripIdAndDistanceFilters());

    if (removeItinerariesWithSameRoutesAndStops) {
      filters.addAll(buildGroupBySameRoutesAndStopsFilter());
    }

    if (sameFirstOrLastTripFilter) {
      filters.add(new SortingFilter(generalizedCostComparator()));
      addRmFilter(filters, new SameFirstOrLastTripFilter());
    }

    if (minBikeParkingDistance > 0) {
      filters.add(
        new RemoveItinerariesWithShortStreetLeg(minBikeParkingDistance, TraverseMode.BICYCLE)
      );
    }

    if (accessibilityScore) {
      filters.add(new AccessibilityScoreFilter(wheelchairMaxSlope));
    }

    if (faresFilter != null) {
      filters.add(faresFilter);
    }

    if (emissionsFilter != null) {
      filters.add(emissionsFilter);
    }

    if (transitAlertService != null) {
      filters.add(new TransitAlertFilter(transitAlertService, getMultiModalStation));
    }

    // Filter transit itineraries on generalized-cost
    if (transitGeneralizedCostFilterParams != null) {
      addRmFilter(
        filters,
        new TransitGeneralizedCostFilter(
          transitGeneralizedCostFilterParams.costLimitFunction(),
          transitGeneralizedCostFilterParams.intervalRelaxFactor()
        )
      );
    }

    // Filter non-transit itineraries on generalized-cost
    if (nonTransitGeneralizedCostLimit != null) {
      addRmFilter(filters, new NonTransitGeneralizedCostFilter(nonTransitGeneralizedCostLimit));
    }

    // Apply all absolute filters AFTER the groupBy filters. Absolute filters are filters that
    // remove elements/ based on the given itinerary properties - not considering other
    // itineraries. This may remove itineraries in the "groupBy" filters that are considered
    // worse than the itineraries removed here. Let take an example, 2 itineraries, A and B, are
    // returned. A have a significant higher cost than B, but share the same long last transit
    // leg. B depart AFTER the latest-departure-time (this may happen if the access is
    // time-shifted). Then, A will be removed by the "group-by" filters(similar to B, but cost
    // is worse). B is removed by the {@link LatestDepartureTimeFilter} below. This is exactly
    // what we want, since both itineraries are none optimal.
    {
      // Filter transit itineraries by comparing against non-transit using generalized-cost
      if (removeTransitWithHigherCostThanBestOnStreetOnly != null) {
        addRmFilter(
          filters,
          new RemoveTransitIfStreetOnlyIsBetterFilter(
            removeTransitWithHigherCostThanBestOnStreetOnly
          )
        );
      }

      if (removeTransitIfWalkingIsBetter) {
        addRmFilter(filters, new RemoveTransitIfWalkingIsBetterFilter());
      }

      if (removeWalkAllTheWayResults) {
        addRmFilter(filters, new RemoveWalkOnlyFilter());
      }

      if (bikeRentalDistanceRatio > 0) {
        addRmFilter(filters, new RemoveBikerentalWithMostlyWalkingFilter(bikeRentalDistanceRatio));
      }

      if (parkAndRideDurationRatio > 0) {
        addRmFilter(
          filters,
          new RemoveParkAndRideWithMostlyWalkingFilter(parkAndRideDurationRatio)
        );
      }
    }

    // Paging related filters - these filters are run after group-by filters to allow a result
    // outside the page to also take effect inside the window. This is debatable but lead to less
    // noise, however it is not deterministic because the result depends on the size of the search-window and
    // where the "cut" between each page is located.
    {
      // Limit to search-window
      if (earliestDepartureTime != null) {
        addRmFilter(filters, new OutsideSearchWindowFilter(earliestDepartureTime, searchWindow));
      }

      // Remove itineraries present in the page retrieved before this page/search.
      if (itineraryPageCut != null) {
        addRmFilter(filters, new PagingFilter(sortOrder, deduplicateSection(), itineraryPageCut));
      }

      // Remove itineraries if max limit is set
      if (maxNumberOfItineraries > 0) {
        filters.add(new SortingFilter(SortOrderComparator.comparator(sortOrder)));
        addRmFilter(
          filters,
          new NumItinerariesFilter(
            maxNumberOfItineraries,
            maxNumberOfItinerariesCropSection,
            numItinerariesFilterResultsConsumer
          )
        );
      }
    }

    // Do the final itineraries sort
    filters.add(new SortingFilter(SortOrderComparator.comparator(sortOrder)));

    // Sandbox filters to decorate itineraries

    if (faresFilter != null) {
      filters.add(faresFilter);
    }

    if (rideHailingFilter != null) {
      filters.add(rideHailingFilter);
    }

    if (stopConsolidationFilter != null) {
      filters.add(stopConsolidationFilter);
    }

    var debugHandler = new DeleteResultHandler(debug, maxNumberOfItineraries);

    return new ItineraryListFilterChain(filters, debugHandler);
  }

  public ItineraryListFilterChainBuilder withTransitAlerts(
    TransitAlertService transitAlertService,
    Function<Station, MultiModalStation> getMultiModalStation
  ) {
    this.transitAlertService = transitAlertService;
    this.getMultiModalStation = getMultiModalStation;

    return this;
  }

  /**
   * If enabled, this adds the filter to remove itineraries which have the same stops and routes.
   * These are sometimes called "time-shifted duplicates" but since those terms have so many
   * meanings we chose to use a long, but descriptive name instead.
   */
  private List<ItineraryListFilter> buildGroupBySameRoutesAndStopsFilter() {
    return List.of(
      new GroupByFilter<>(
        GroupBySameRoutesAndStops::new,
        List.of(
          new SortingFilter(SortOrderComparator.comparator(sortOrder)),
          new DeletionFlaggingFilter(new MaxLimitFilter(GroupBySameRoutesAndStops.TAG, 1))
        )
      )
    );
  }

  /**
   * These filters will group the itineraries by the main-legs and reduce the number of itineraries
   * in each group. The main legs is the legs that together constitute more than a given　percentage
   * of the total travel distance.
   * <p>
   * Each group is filtered using generalized-cost, keeping only the itineraries with the lowest
   * cost. If there is a tie, the filter look at the number-of-transfers as a tiebreaker.
   * <p>
   * The filter name is dynamically created: similar-legs-filter-68p-1
   */
  private List<ItineraryListFilter> buildGroupByTripIdAndDistanceFilters() {
    var sysTags = new ArrayList<String>();

    List<GroupBySimilarity> groupBy = groupBySimilarity
      .stream()
      .sorted(Comparator.comparingDouble(o -> o.groupByP))
      .toList();

    List<ItineraryListFilter> groupByFilters = new ArrayList<>();

    for (GroupBySimilarity group : groupBy) {
      String tag =
        "similar-legs-filter-%.0fp-%dx".formatted(
            100d * group.groupByP,
            group.maxNumOfItinerariesPerGroup
          );
      sysTags.add(tag);

      List<ItineraryListFilter> nested = new ArrayList<>();

      if (group.nestedGroupingByAllSameStations) {
        final String innerGroupName = tag + "-group-by-all-same-stations";
        sysTags.add(tag);
        nested.add(
          new GroupByFilter<>(
            GroupByAllSameStations::new,
            List.of(
              new SortingFilter(generalizedCostComparator()),
              new DeletionFlaggingFilter(new MaxLimitFilter(innerGroupName, 1))
            )
          )
        );
      }

      if (group.maxCostOtherLegsFactor > 1.0) {
        var flagger = new OtherThanSameLegsMaxGeneralizedCostFilter(group.maxCostOtherLegsFactor);
        sysTags.add(flagger.name());
        addRmFilter(nested, flagger);
      }

      nested.add(new SortingFilter(generalizedCostComparator()));

      addRmFilter(nested, new MaxLimitFilter(tag, group.maxNumOfItinerariesPerGroup));

      nested.add(new RemoveDeletionFlagForLeastTransfersItinerary(sysTags));

      groupByFilters.add(
        new GroupByFilter<>(it -> new GroupByDistance(it, group.groupByP), nested)
      );
    }

    return groupByFilters;
  }

  private ListSection deduplicateSection() {
    return maxNumberOfItinerariesCropSection.invert();
  }

  private static void addRmFilter(
    List<ItineraryListFilter> filters,
    ItineraryDeletionFlagger removeFilter
  ) {
    filters.add(new DeletionFlaggingFilter(removeFilter));
  }
}
