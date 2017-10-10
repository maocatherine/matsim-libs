/**
 * 
 */
package org.matsim.core.mobsim.qsim.qnetsimengine;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;

/**
 * @author kainagel
 *
 */
interface TurnAcceptanceLogic {
	
	enum AcceptTurn { GO, WAIT, ABORT }

	AcceptTurn isAcceptingTurn(Link currentLink, QLaneI currentLane, Id<Link> nextLinkId, QLinkI nextQLink, QVehicle veh);

}
