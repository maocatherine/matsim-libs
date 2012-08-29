/* *********************************************************************** *
 * project: org.matsim.*
 * CurrentLegToMeetingPointReplanner.java
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2011 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */

package playground.christoph.evacuation.withinday.replanning.replanners;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.core.api.experimental.facilities.ActivityFacility;
import org.matsim.core.mobsim.qsim.InternalInterface;
import org.matsim.core.mobsim.qsim.agents.PlanBasedWithinDayAgent;
import org.matsim.core.population.ActivityImpl;
import org.matsim.core.population.PlanImpl;
import org.matsim.core.scenario.ScenarioImpl;
import org.matsim.withinday.replanning.replanners.interfaces.WithinDayDuringLegReplanner;
import org.matsim.withinday.utils.EditRoutes;
import org.matsim.withinday.utils.ReplacePlanElements;

import playground.christoph.evacuation.mobsim.decisiondata.DecisionDataProvider;
import playground.christoph.evacuation.mobsim.decisiondata.PersonDecisionData;

/**
 * Move the destination of the next activity to the agent's household,
 * change the activity type to "meetHoushold" and remove all activities
 * that are scheduled at a later point in time.
 * 
 * @author cdobler
 */
public class CurrentLegToMeetingPointReplanner extends WithinDayDuringLegReplanner {

	private static final String activityType = "meetHousehold";
	
	protected final DecisionDataProvider decisionDataProvider;
	
	/*package*/ CurrentLegToMeetingPointReplanner(Id id, Scenario scenario,
			InternalInterface internalInterface, DecisionDataProvider decisionDataProvider) {
		super(id, scenario, internalInterface);
		this.decisionDataProvider = decisionDataProvider;
	}

	@Override
	public boolean doReplanning(PlanBasedWithinDayAgent withinDayAgent) {
				
		// If we don't have a valid Replanner.
		if (this.routeAlgo == null) return false;

		// If we don't have a valid WithinDayPersonAgent
		if (withinDayAgent == null) return false;
		
		PlanImpl executedPlan = (PlanImpl) withinDayAgent.getSelectedPlan();

		// If we don't have an executed plan
		if (executedPlan == null) return false;

		int currentLegIndex = withinDayAgent.getCurrentPlanElementIndex();
		int currentLinkIndex = withinDayAgent.getCurrentRouteLinkIdIndex();
		Activity nextActivity = (Activity) executedPlan.getPlanElements().get(withinDayAgent.getCurrentPlanElementIndex() + 1);
		
		/*
		 * Create new Activity at the meeting point.
		 */
		PersonDecisionData pdd = decisionDataProvider.getPersonDecisionData(withinDayAgent.getId());
		Id householdId = pdd.getHouseholdId();
		Id meetingPointId = decisionDataProvider.getHouseholdDecisionData(householdId).getMeetingPointFacilityId(); 		
		ActivityFacility meetingFacility = ((ScenarioImpl) scenario).getActivityFacilities().getFacilities().get(meetingPointId);
		Activity meetingActivity = scenario.getPopulation().getFactory().createActivityFromLinkId(activityType, meetingFacility.getLinkId());
		((ActivityImpl) meetingActivity).setFacilityId(meetingPointId);
		((ActivityImpl)meetingActivity).setCoord(meetingFacility.getCoord());
		meetingActivity.setEndTime(Double.POSITIVE_INFINITY);

		/*
		 * If the Agent wants to perform the next Activity at the current Link we
		 * cannot replace that Activity at the moment (Simulation logic...).
		 * Therefore we set the Duration of the next Activity to 0 and add a new
		 * Meeting Activity afterwards.
		 */
		// TODO: Check whether this is still true since we have resetCaches now...
//		if (withinDayAgent.getCurrentLinkId().equals(nextActivity.getLinkId())) {
//			nextActivity.setStartTime(this.time);
//			nextActivity.setEndTime(this.time);
//
//			// Remove all legs and activities after the activity after the next activity.
//			int nextActivityIndex = executedPlan.getActLegIndex(nextActivity);
//			
//			while (executedPlan.getPlanElements().size() - 1 > nextActivityIndex) {
//				executedPlan.removeActivity(executedPlan.getPlanElements().size() - 1);
//			}			
//
//			Leg currentLeg = (Leg) executedPlan.getPlanElements().get(withinDayAgent.getCurrentPlanElementIndex());
//			executedPlan.createAndAddLeg(currentLeg.getMode());
//			executedPlan.addActivity(meetingActivity);
//						
//			int position = executedPlan.getPlanElements().size() - 2;
//			
//			new EditRoutes().replanFutureLegRoute(executedPlan, position, routeAlgo);
//		}
//		else {			
			new ReplacePlanElements().replaceActivity(executedPlan, nextActivity, meetingActivity);
			
			/*
			 * If the agent has just departed from its home facility (currentLegIndex = 0), then
			 * the simulation does not allow stops again at the same link (queue logic). Therefore
			 * we increase the currentLegIndex by one which means that the agent will drive a loop
			 * and then return to this link again.
			 * TODO: remove this, if the queue logic is adapted...
			 */
			if (currentLinkIndex == 0) currentLinkIndex++;
			
			// new Route for current Leg
			new EditRoutes().replanCurrentLegRoute(executedPlan, currentLegIndex, currentLinkIndex, routeAlgo, time);
			
			// Remove all legs and activities after the next activity.
			int nextActivityIndex = executedPlan.getActLegIndex(meetingActivity);
			
			while (executedPlan.getPlanElements().size() - 1 > nextActivityIndex) {
				executedPlan.removeActivity(executedPlan.getPlanElements().size() - 1);
			}			
//		}
		
		// Finally reset the cached Values of the PersonAgent - they may have changed!
		withinDayAgent.resetCaches();
		
		return true;
	}
}