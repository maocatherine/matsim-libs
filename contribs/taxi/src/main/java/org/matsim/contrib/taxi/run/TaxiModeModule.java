/*
 * *********************************************************************** *
 * project: org.matsim.*
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2018 by the members listed in the COPYING,        *
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
 * *********************************************************************** *
 */

package org.matsim.contrib.taxi.run;

import org.matsim.contrib.dvrp.analysis.ExecutedScheduleCollector;
import org.matsim.contrib.dvrp.fleet.FleetModule;
import org.matsim.contrib.dvrp.router.DvrpModeRoutingModule;
import org.matsim.contrib.dvrp.router.DvrpModeRoutingNetworkModule;
import org.matsim.contrib.dvrp.router.TimeAsTravelDisutility;
import org.matsim.contrib.dvrp.run.AbstractDvrpModeModule;
import org.matsim.contrib.dvrp.run.DvrpModes;
import org.matsim.contrib.dvrp.run.ModalProviders;
import org.matsim.contrib.taxi.analysis.TaxiEventSequenceCollector;
import org.matsim.contrib.taxi.benchmark.TaxiBenchmarkStats;
import org.matsim.contrib.taxi.fare.TaxiFareHandler;
import org.matsim.contrib.taxi.util.stats.TaxiStatsDumper;
import org.matsim.core.controler.IterationCounter;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.router.costcalculators.TravelDisutilityFactory;
import org.matsim.core.router.speedy.SpeedyALTFactory;

/**
 * @author michalm
 */
public final class TaxiModeModule extends AbstractDvrpModeModule {
	private final TaxiConfigGroup taxiCfg;

	public TaxiModeModule(TaxiConfigGroup taxiCfg) {
		super(taxiCfg.getMode());
		this.taxiCfg = taxiCfg;
	}

	@Override
	public void install() {
		DvrpModes.registerDvrpMode(binder(), getMode());

		install(new DvrpModeRoutingNetworkModule(getMode(), taxiCfg.isUseModeFilteredSubnetwork()));
		bindModal(TravelDisutilityFactory.class).toInstance(TimeAsTravelDisutility::new);

		install(new DvrpModeRoutingModule(getMode(), new SpeedyALTFactory()));

		install(new FleetModule(getMode(), taxiCfg.getTaxisFileUrl(getConfig().getContext()),
				taxiCfg.isChangeStartLinkToLastLinkInSchedule()));

		taxiCfg.getTaxiFareParams()
				.ifPresent(params -> addEventHandlerBinding().toInstance(new TaxiFareHandler(getMode(), params)));

		bindModal(TaxiStatsDumper.class).toProvider(ModalProviders.createProvider(getMode(),
				getter -> new TaxiStatsDumper(taxiCfg, getter.get(OutputDirectoryHierarchy.class),
						getter.get(IterationCounter.class), getter.getModal(ExecutedScheduleCollector.class),
						getter.getModal(TaxiEventSequenceCollector.class)))).asEagerSingleton();
		addControlerListenerBinding().to(modalKey(TaxiStatsDumper.class));
	}
}
