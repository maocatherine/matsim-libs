package org.matsim.contrib.accessibility;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.contrib.accessibility.gis.SpatialGrid;
import org.matsim.contrib.accessibility.interfaces.SpatialGridDataExchangeInterface;
import org.matsim.contrib.accessibility.interfaces.ZoneDataExchangeInterface;
import org.matsim.contrib.accessibility.utils.AggregationObject;
import org.matsim.contrib.accessibility.utils.Benchmark;
import org.matsim.contrib.accessibility.utils.Distances;
import org.matsim.contrib.accessibility.utils.LeastCostPathTreeExtended;
import org.matsim.contrib.accessibility.utils.NetworkUtil;
import org.matsim.contrib.accessibility.utils.ProgressBar;
import org.matsim.contrib.matrixbasedptrouter.PtMatrix;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.PlanCalcScoreConfigGroup;
import org.matsim.core.controler.Controler;
import org.matsim.core.gbl.Gbl;
import org.matsim.core.network.NetworkImpl;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.trafficmonitoring.FreeSpeedTravelTime;
import org.matsim.facilities.ActivityFacilities;
import org.matsim.facilities.ActivityFacilitiesImpl;
import org.matsim.facilities.ActivityFacility;
import org.matsim.roadpricing.RoadPricingScheme;
import org.matsim.roadpricing.RoadPricingSchemeImpl.Cost;
import org.matsim.utils.leastcostpathtree.LeastCostPathTree;

/**
 * improvements aug'12<ul>
 * <li> accessibility calculation of unified for cell- and zone-base approach
 * <li> large computing savings due reduction of "least cost path tree" execution:
 *   In a pre-processing step all nearest nodes of measuring points (origins) are determined. 
 *   The "least cost path tree" for measuring points with the same nearest node are now only executed once. 
 *   Only the cost calculations from the measuring point to the network is done individually.
 * </ul><p/>  
 * improvements nov'12<ul>
 * <li> bug fixed aggregatedOpportunities method for compound cost factors like time and distance    
 * </ul><p/>
 * improvements jan'13<ul>
 * <li> added pt for accessibility calculation
 * </ul><p/>
 * 
 * improvements june'13<ul>
 * <li> take "main" (reference to matsim4urbansim) out
 * <li> aggregation of opportunities adjusted to handle facilities
 * <li> zones are taken out
 * <li> replaced [[??]]
 * </ul> 
 * <p/> 
 * Design comments:<ul>
 * <li> yyyy This class is quite brittle, since it does not use a central disutility object, but produces its own.  Should be changed.
 * </ul>
 *     
 * @author thomas, knagel
 *
 */
/*package*/ final class AccessibilityControlerListenerDelegate {

	private static final Logger log = Logger.getLogger(AccessibilityControlerListenerDelegate.class);

	// measuring points (origins) for accessibility calculation
	private ActivityFacilitiesImpl measuringPoints;
	// containing parcel coordinates for accessibility feedback
	private ActivityFacilitiesImpl parcels;
	// destinations, opportunities like jobs etc ...
	private AggregationObject[] aggregatedOpportunities;
	
	// storing the accessibility results
	private Map<Modes4Accessibility,SpatialGrid> accessibilityGrids = new HashMap<Modes4Accessibility,SpatialGrid>() ;

	private Map<Modes4Accessibility,Boolean> isComputingMode = new HashMap<Modes4Accessibility,Boolean>() ;
	private Map<Modes4Accessibility, AccessibilityContributionCalculator> calculators = new HashMap<>();

	private PtMatrix ptMatrix;

	private RoadPricingScheme scheme ;

	private ArrayList<SpatialGridDataExchangeInterface> spatialGridDataExchangeListenerList = null;
	private ArrayList<ZoneDataExchangeInterface> zoneDataExchangeListenerList = null;

	// accessibility parameter

	// yy I find it quite awkward to generate all these lines of computational code just to copy variables from one place to the other. I assume that
	// one learns to do so in adapter classes, since one does not want changes on one side of the adapter to trigger to the other side of the adapter. 
	// However, the following alternatives seem feasible:
	// * replace those package-wide variables by getters that take the info directly from the other side so that the structure becomes clear
	// * alternatively, use a more intelligent data structure in the sense of beta[car][TD].
	// kai, jul'13

	private boolean useRawSum	; //= false;
	private double logitScaleParameter;
	private double inverseOfLogitScaleParameter;
	private double betaWalkTT;	// in MATSim this is [utils/h]: cnScoringGroup.getTravelingWalk_utils_hr() - cnScoringGroup.getPerforming_utils_hr()
	private double betaWalkTD;	// in MATSim this is 0 !!! since getMonetaryDistanceCostRateWalk doesn't exist: 
	private double betaWalkTMC;	// in MATSim this is [utils/money]: cnScoringGroup.getMarginalUtilityOfMoney()

	private double walkSpeedMeterPerHour = -1;
	private Benchmark benchmark;
	
	// counter for warning that capacities are not used so far ... in order not to give the same warning multiple times; dz, apr'14
	private static int cnt = 0 ;

	protected boolean urbansimMode = true;

	AccessibilityControlerListenerDelegate() {
		for ( Modes4Accessibility mode : Modes4Accessibility.values() ) {
			this.isComputingMode.put( mode, false ) ;
		}
	}

	// XXX Ugly but temporary
	final void initDefaultContributionCalculators( final Controler controler ) {
		calculators.put(
				Modes4Accessibility.car,
				new NetworkModeAccessibilityContributionCalculator(
						controler.getLinkTravelTimes(),
						controler.getTravelDisutilityFactory(),
						controler.getScenario() ) );
		calculators.put(
				Modes4Accessibility.freeSpeed,
				new NetworkModeAccessibilityContributionCalculator(
						new FreeSpeedTravelTime(),
						controler.getTravelDisutilityFactory(),
						controler.getScenario() ) );
		calculators.put(
				Modes4Accessibility.walk,
				new ConstantSpeedAccessibilityContributionCalculator(
						TransportMode.walk,
						controler.getScenario() ) );
		calculators.put(
				Modes4Accessibility.bike,
				new ConstantSpeedAccessibilityContributionCalculator(
						TransportMode.bike,
						controler.getScenario() ) );
		calculators.put(
				Modes4Accessibility.pt,
				new MatrixBasedPtAccessibilityContributionCalculator(
						ptMatrix,
						controler.getConfig() ) );
	}
	
	/**
	 * setting parameter for accessibility calculation
	 * @param config TODO
	 */
	final void initAccessibilityParameters(Config config) {

		AccessibilityConfigGroup moduleAPCM = ConfigUtils.addOrGetModule(config, AccessibilityConfigGroup.GROUP_NAME, AccessibilityConfigGroup.class);

		PlanCalcScoreConfigGroup planCalcScoreConfigGroup = config.planCalcScore();

		if (planCalcScoreConfigGroup.getOrCreateModeParams(TransportMode.car).getMarginalUtilityOfDistance() != 0.) {
			log.error("marginal utility of distance for car different from zero but not used in accessibility computations");
		}
		if (planCalcScoreConfigGroup.getOrCreateModeParams(TransportMode.pt).getMarginalUtilityOfDistance() != 0.) {
			log.error("marginal utility of distance for pt different from zero but not used in accessibility computations");
		}
		if (planCalcScoreConfigGroup.getOrCreateModeParams(TransportMode.bike).getMonetaryDistanceCostRate() != 0.) {
			log.error("monetary distance cost rate for bike different from zero but not used in accessibility computations");
		}
		if (planCalcScoreConfigGroup.getOrCreateModeParams(TransportMode.walk).getMonetaryDistanceCostRate() != 0.) {
			log.error("monetary distance cost rate for walk different from zero but not used in accessibility computations");
		}

		useRawSum = moduleAPCM.isUsingRawSumsWithoutLn();
		logitScaleParameter = planCalcScoreConfigGroup.getBrainExpBeta();
		inverseOfLogitScaleParameter = 1 / (logitScaleParameter); // logitScaleParameter = same as brainExpBeta on 2-aug-12. kai
		walkSpeedMeterPerHour = config.plansCalcRoute().getTeleportedModeSpeeds().get(TransportMode.walk) * 3600.;

		betaWalkTT = planCalcScoreConfigGroup.getTravelingWalk_utils_hr() - planCalcScoreConfigGroup.getPerforming_utils_hr();
		betaWalkTD = planCalcScoreConfigGroup.getMarginalUtlOfDistanceWalk();
		betaWalkTMC = -planCalcScoreConfigGroup.getMarginalUtilityOfMoney();
	}

	/**
	 * This aggregates the disutilities Vjk to get from node j to all k that are attached to j.
	 * Finally the sum(Vjk) is assigned to node j, which is done in this method.
	 * 
	 *     j---k1 
	 *     |\
	 *     | \
	 *     k2 k3
	 * 
	 * @param opportunities such as workplaces, either given at a parcel- or zone-level
	 * @param network giving the road network
	 * @return the sum of disutilities Vjk, i.e. the disutilities to reach all opportunities k that are assigned to j from node j 
	 */
	final AggregationObject[] aggregatedOpportunities(final ActivityFacilities opportunities, Network network){
		// yyyy this method ignores the "capacities" of the facilities.  kai, mar'14

		log.info("Aggregating " + opportunities.getFacilities().size() + " opportunities with identical nearest node ...");
		Map<Id<Node>, AggregationObject> opportunityClusterMap = new ConcurrentHashMap<Id<Node>, AggregationObject>();
		ProgressBar bar = new ProgressBar( opportunities.getFacilities().size() );

		for ( ActivityFacility opportunity : opportunities.getFacilities().values() ) {
			bar.update();

			Node nearestNode = ((NetworkImpl)network).getNearestNode( opportunity.getCoord() );

			// get Euclidian distance to nearest node
			double distance_meter 	= NetworkUtils.getEuclidianDistance(opportunity.getCoord(), nearestNode.getCoord());
			double walkTravelTime_h = distance_meter / this.walkSpeedMeterPerHour;

			double VjkWalkTravelTime	= this.betaWalkTT * walkTravelTime_h;
			double VjkWalkPowerTravelTime=0.; // this.betaWalkTTPower * (walkTravelTime_h * walkTravelTime_h);
			double VjkWalkLnTravelTime	= 0.; // this.betaWalkLnTT * Math.log(walkTravelTime_h);

			double VjkWalkDistance 		= this.betaWalkTD * distance_meter;
			double VjkWalkPowerDistnace	= 0.; //this.betaWalkTDPower * (distance_meter * distance_meter);
			double VjkWalkLnDistance 	= 0.; //this.betaWalkLnTD * Math.log(distance_meter);

			double VjkWalkMoney			= this.betaWalkTMC * 0.; 			// no monetary costs for walking
			double VjkWalkPowerMoney	= 0.; //this.betaWalkTDPower * 0.; 	// no monetary costs for walking
			double VjkWalkLnMoney		= 0.; //this.betaWalkLnTMC *0.; 	// no monetary costs for walking

			double expVjk					= Math.exp(this.logitScaleParameter * (VjkWalkTravelTime + VjkWalkPowerTravelTime + VjkWalkLnTravelTime +
					VjkWalkDistance   + VjkWalkPowerDistnace   + VjkWalkLnDistance +
					VjkWalkMoney      + VjkWalkPowerMoney      + VjkWalkLnMoney) );
			// add Vjk to sum
//			if( opportunityClusterMap.containsKey( nearestNode.getId() ) ){
//				AggregationObject jco = opportunityClusterMap.get( nearestNode.getId() );
//				jco.addObject( opportunity.getId(), expVjk);
//			} else {
//				// assign Vjk to given network node
//				opportunityClusterMap.put(
//						nearestNode.getId(),
//						new AggregationObject(opportunity.getId(), null, null, nearestNode, expVjk) 
//						);
//			}
			AggregationObject jco = opportunityClusterMap.get( nearestNode.getId() ) ;
			if ( jco == null ) {
				jco = new AggregationObject(opportunity.getId(), null, null, nearestNode, 0. ); // initialize with zero!
				opportunityClusterMap.put( nearestNode.getId(), jco ) ; 
			}
			if ( cnt == 0 ) {
				cnt++;
				log.warn("ignoring the capacities of the facilities");
				log.warn(Gbl.ONLYONCE);
			}
			jco.addObject( opportunity.getId(), expVjk ) ;
			// yyyy if we knew the activity type, we could to do capacities as follows:
//			ActivityOption opt = opportunity.getActivityOptions().get("type") ;
//			Assert.assertNotNull(opt);
//			final double capacity = opt.getCapacity();
//			Assert.assertNotNull(capacity) ; // we do not know what that would mean
//			if ( capacity < Double.POSITIVE_INFINITY ) { // this is sometimes the value of "undefined" 
//				jco.addObject( opportunity.getId(), capacity * expVjk ) ;
//			} else {
//				jco.addObject( opportunity.getId(), expVjk ) ; // fix if capacity is "unknown".
//			}
			
		}
		// convert map to array
		AggregationObject jobClusterArray []  = new AggregationObject[ opportunityClusterMap.size() ];
		Iterator<AggregationObject> jobClusterIterator = opportunityClusterMap.values().iterator();
		for(int i = 0; jobClusterIterator.hasNext(); i++) {
			jobClusterArray[i] = jobClusterIterator.next();
		}

		// yy maybe replace by following?? Needs to be tested.  kai, mar'14
//		AggregateObject2NearestNode[] jobClusterArray = (AggregateObject2NearestNode[]) opportunityClusterMap.values().toArray() ;

		log.info("Aggregated " + opportunities.getFacilities().size() + " number of opportunities to " + jobClusterArray.length + " nodes.");

		return jobClusterArray;
	}

	
	final void accessibilityComputation(
			AccessibilityCSVWriter writer,
			Scenario scenario,
			boolean isGridBased ) {

		SumOfExpUtils[] gcs = new SumOfExpUtils[Modes4Accessibility.values().length] ;
		// this could just be a double array, or a Map.  Not using a Map for computational speed reasons (untested);
		// not using a simple double array for type safety in long argument lists. kai, feb'14
		for ( int ii=0 ; ii<gcs.length ; ii++ ) {
			gcs[ii] = new SumOfExpUtils() ;
		}


		// this data structure condense measuring points (origins) that have the same nearest node on the network ...
		Map<Id<Node>,ArrayList<ActivityFacility>> aggregatedOrigins = new ConcurrentHashMap<Id<Node>, ArrayList<ActivityFacility>>();
		// ========================================================================
		for ( ActivityFacility aFac : measuringPoints.getFacilities().values() ) {

			// determine nearest network node (from- or toNode) based on the link
			Node fromNode = NetworkUtils.getCloserNodeOnLink(aFac.getCoord(), ((NetworkImpl)scenario.getNetwork()).getNearestLinkExactly(aFac.getCoord()));

			// this is used as a key for hash map lookups
			Id<Node> nodeId = fromNode.getId();

			// create new entry if key does not exist!
			if(!aggregatedOrigins.containsKey(nodeId)) {
				aggregatedOrigins.put(nodeId, new ArrayList<ActivityFacility>());
			}
			// assign measure point (origin) to it's nearest node
			aggregatedOrigins.get(nodeId).add(aFac);
		}
		// ========================================================================

		log.info("");
		log.info("Number of measurement points (origins): " + measuringPoints.getFacilities().values().size());
		log.info("Number of aggregated measurement points (origins): " + aggregatedOrigins.size());
		log.info("Now going through all origins:");

		ProgressBar bar = new ProgressBar( aggregatedOrigins.size() );
		// ========================================================================
		// go through all nodes (keys) that have a measuring point (origin) assigned
		for ( Id<Node> nodeId : aggregatedOrigins.keySet() ) {

			bar.update();

			Node fromNode = scenario.getNetwork().getNodes().get( nodeId );

			for ( AccessibilityContributionCalculator calculator : calculators.values() ) {
				calculator.notifyNewOriginNode( fromNode );
			}

			// get list with origins that are assigned to "fromNode"
			for ( ActivityFacility origin : aggregatedOrigins.get( nodeId ) ) {
				assert( origin.getCoord() != null );

                    for ( int ii = 0 ; ii<gcs.length ; ii++ ) {
                        gcs[ii].reset();
                    }

                    // --------------------------------------------------------------------------------------------------------------
                    // goes through all opportunities, e.g. jobs, (nearest network node) and calculate/add their exp(U) contributions:
                    for ( int i = 0; i < this.aggregatedOpportunities.length; i++ ) {
                        final AggregationObject aggregatedFacility = this.aggregatedOpportunities[i];

                        computeAndAddExpUtilContributions(
                                scenario,
                                gcs,
                                origin,
                                fromNode,
                                aggregatedFacility);
                    }
                    // --------------------------------------------------------------------------------------------------------------
                    // What does the aggregation of the starting locations save if we do the just ended loop for all starting
                    // points separately anyways?  Answer: The trees need to be computed only once.  (But one could save more.) kai, feb'14

                    // aggregated value
                    Map< Modes4Accessibility, Double> accessibilities  = new HashMap< Modes4Accessibility, Double >() ;

                    for ( Modes4Accessibility mode : Modes4Accessibility.values() ) {
                        if ( this.isComputingMode.get(mode) ) {
                            if(!useRawSum){ 	// get log sum
                                accessibilities.put( mode, inverseOfLogitScaleParameter * Math.log( gcs[mode.ordinal()].getSum() ) ) ;
                            } else {
                                // this was used by IVT within SustainCity.  Not sure if we should main this; they could, after all, just exp the log results. kai, may'15
                                accessibilities.put( mode, gcs[mode.ordinal()].getSum() ) ;
    //							accessibilities.put( mode, inverseOfLogitScaleParameter * gcs[mode.ordinal()].getSum() ) ;
                                // yyyy why _multiply_ with "inverseOfLogitScaleParameter"??  If anything, would need to take the power:
                                // a * ln(b) = ln( b^a ).  kai, jan'14
                            }
                            if( isGridBased ){ // only for cell-based accessibility computation
                                // assign log sums to current starZone[[???]] object and spatial grid
                                this.accessibilityGrids.get(mode).setValue( accessibilities.get(mode), origin.getCoord().getX(), origin.getCoord().getY() ) ;
                            }
                        }
                    }

                    if ( this.urbansimMode ) {
                        // writing measured accessibilities for current measuring point
                        writer.writeRecord(origin, fromNode, accessibilities ) ;
                        // (I think the above is the urbansim output.  Better not touch it. kai, feb'14)
                    }

                    if(this.zoneDataExchangeListenerList != null){
                        for(int i = 0; i < this.zoneDataExchangeListenerList.size(); i++)
                            this.zoneDataExchangeListenerList.get(i).setZoneAccessibilities(origin, accessibilities );
                    }

                    // yy The above storage logic is a bit odd (probably historically grown and then never cleaned up):
                    // * For urbansim, the data is directly written to file and then forgotten.
                    // * In addition, the cell-based data is memorized for writing it in a different format (spatial grid, for R, not used any more).
                    // * Since the zone-based data is not memorized, there is a specific mechanism to set the value in registered listeners.
                    // * The zone-based listener also works for cell-based data.
                    // * I don't think that it is used anywhere except in one test.  Easiest would be to get rid of this but it may not be completely
                    //  easy to fix the test (maybe memorize all accessibility values in all cases).
				// It might be a lot easier to just memorize all the data right away.
				// kai, may'15


			}

		}
		// ========================================================================
	}

	
	private void computeAndAddExpUtilContributions(
			Scenario scenario,
			SumOfExpUtils[] gcs,
			ActivityFacility origin,
			Node fromNode,
			final AggregationObject aggregatedFacility) {
        // get the nearest link:
        Link nearestLink = ((NetworkImpl)scenario.getNetwork()).getNearestLinkExactly(origin.getCoord());

        // captures the distance (as walk time) between the origin via the link to the node:
        Distances distance = NetworkUtil.getDistances2Node(origin.getCoord(), nearestLink, fromNode);

		for ( Map.Entry<Modes4Accessibility, AccessibilityContributionCalculator> calculatorEntry : calculators.entrySet() ) {
			if ( !isComputingMode.get( calculatorEntry.getKey() ) ) continue; // XXX should be configured by adding only the relevant calculators
			final double expVhk = calculatorEntry.getValue().computeContributionOfOpportunity( origin , aggregatedFacility );
			gcs[ calculatorEntry.getKey().ordinal() ].addExpUtils( expVhk );
		}
	}

	public void setComputingAccessibilityForMode( Modes4Accessibility mode, boolean val ) {
		this.isComputingMode.put( mode, val ) ;
	}


	/**
	 * This adds listeners to write out accessibility results for parcels in UrbanSim format
	 * @param l
	 */
	public void addSpatialGridDataExchangeListener(SpatialGridDataExchangeInterface l){
		if(this.spatialGridDataExchangeListenerList == null)
			this.spatialGridDataExchangeListenerList = new ArrayList<SpatialGridDataExchangeInterface>();

		log.info("Adding new SpatialGridDataExchange listener...");
		this.spatialGridDataExchangeListenerList.add(l);
		log.info("... done!");
	}

	
	/**
	 * This adds listeners to write out accessibility results for parcels in UrbanSim format
	 * @param l
	 */
	public void addZoneDataExchangeListener(ZoneDataExchangeInterface l){
		if(this.zoneDataExchangeListenerList == null)
			this.zoneDataExchangeListenerList = new ArrayList<ZoneDataExchangeInterface>();

		log.info("Adding new SpatialGridDataExchange listener...");
		this.zoneDataExchangeListenerList.add(l);
		log.info("... done!");
	}

	
	// ////////////////////////////////////////////////////////////////////
	// inner classes
	// ////////////////////////////////////////////////////////////////////


	/**
	 * Set to true if you are using this module in urbansim mode.  With false, some (or all) of the m4u output files are not written
	 * (since they cannot be modified anyways).
	 */
	public void setUrbansimMode(boolean urbansimMode) {
		this.urbansimMode = urbansimMode;
	}

	public Map<Modes4Accessibility,SpatialGrid> getAccessibilityGrids() {
		return accessibilityGrids;
	}

	public ActivityFacilitiesImpl getParcels() {
		return parcels;
	}

	public void setParcels(ActivityFacilitiesImpl parcels) {
		this.parcels = parcels;
	}

	public AggregationObject[] getAggregatedOpportunities() {
		return aggregatedOpportunities;
	}

	public void setAggregatedOpportunities(AggregationObject[] aggregatedOpportunities) {
		this.aggregatedOpportunities = aggregatedOpportunities;
	}

	public Map<Modes4Accessibility, Boolean> getIsComputingMode() {
		return isComputingMode;
	}

	public RoadPricingScheme getScheme() {
		return scheme;
	}

	public void setScheme(RoadPricingScheme scheme) {
		this.scheme = scheme;
	}

	public ArrayList<SpatialGridDataExchangeInterface> getSpatialGridDataExchangeListenerList() {
		return spatialGridDataExchangeListenerList;
	}

	public Benchmark getBenchmark() {
		return benchmark;
	}

	public void setBenchmark(Benchmark benchmark) {
		this.benchmark = benchmark;
	}


	/**
	 * stores travel disutilities for different modes
	 */
	static class SumOfExpUtils {
		// could just use Map<Modes4Accessibility,Double>, but since it is fairly far inside the loop, I leave it with primitive
		// variables on the (unfounded) intuition that this helps with computational speed.  kai, 

		private double sum  	= 0.;

		void reset() {
			this.sum		  	= 0.;
		}

		void addExpUtils(double val){
			this.sum += val;
		}

		double getSum(){
			return this.sum;
		}
	}

	public void setPtMatrix(PtMatrix ptMatrix) {
		this.ptMatrix = ptMatrix;
	}

	ActivityFacilitiesImpl getMeasuringPoints() {
		return measuringPoints;
	}


	void setMeasuringPoints(ActivityFacilitiesImpl measuringPoints) {
		this.measuringPoints = measuringPoints;
	}

}
