package com.conveyal.r5.profile.entur.rangeraptor.multicriteria;

import com.conveyal.r5.profile.entur.api.transit.TransferLeg;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.AbstractStopArrival;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.TransitStopArrival;
import com.conveyal.r5.profile.entur.rangeraptor.transit.CostCalculator;
import com.conveyal.r5.profile.entur.rangeraptor.view.DebugHandler;
import com.conveyal.r5.profile.entur.rangeraptor.view.StopArrivalView;

/**
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
class EgressStopArrivals<T extends TripScheduleInfo> extends StopArrivals<T> {

    private final TransferLeg egressLeg;
    private final DestinationArrivals<T> destinationArrivals;
    private final CostCalculator costCalculator;

    EgressStopArrivals(
            TransferLeg egressLeg,
            DestinationArrivals<T> destinationArrivals,
            CostCalculator costCalculator,
            DebugHandler<StopArrivalView<T>> debugHandler
    ) {
        super(debugHandler);
        this.egressLeg = egressLeg;
        this.destinationArrivals = destinationArrivals;
        this.costCalculator = costCalculator;
    }

    @Override
    public boolean add(AbstractStopArrival<T> arrival) {
        if(!arrival.arrivedByTransit()) {
            return super.add(arrival);
        }
        if(super.add(arrival)) {
            destinationArrivals.transferToDestination(
                    (TransitStopArrival<T>) arrival,
                    egressLeg,
                    costCalculator.walkCost(egressLeg.durationInSeconds())
            );
            return true;
        }
        return false;
    }
}
