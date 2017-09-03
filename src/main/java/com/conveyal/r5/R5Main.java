package com.conveyal.r5;

import com.conveyal.r5.analyst.broker.BrokerMain;
import com.conveyal.r5.analyst.cluster.AnalystWorker;
import com.conveyal.r5.point_to_point.PointToPointRouterServer;
import com.conveyal.r5.publish.StaticMain;
import com.conveyal.r5.publish.StaticServer;
import com.conveyal.r5.publish.StaticSiteRequest;
import com.conveyal.r5.streets.EdgeStore;
import com.conveyal.r5.streets.VertexStore;
import com.conveyal.r5.visualizer.RaptorDebugger;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;

/**
 * Main entry point for R5.
 * Currently only supports starting up Analyst components (not plain old journey planning).
 * This will start up either an Analyst worker or a broker depending on the first argument.
 */
public class R5Main {
    public static void main (String... args) throws Exception {
        System.out.println("__________________\n" +
                "___  __ \\__  ____/\n" +
                "__  /_/ /_____ \\  \n" +
                "_  _, _/ ____/ /  \n" +
                "/_/ |_| /_____/   \n" +
                "                ");

        // Pull argument 0 off as the sub-command,
        // then pass the remaining args (1..n) on to that subcommand.
        String command = args[0];
        String[] commandArguments = Arrays.copyOfRange(args, 1, args.length);
        if ("broker".equals(command)) {
            BrokerMain.main(commandArguments);
        } else if ("worker".equals(command)) {
            AnalystWorker.main(commandArguments);
        } else if ("static".equals(command)) {
            StaticMain.main(commandArguments);
        } else if ("static-server".equals(command)) {
            StaticServer.main(commandArguments);
        } else if ("point".equals(command)) {
            PointToPointRouterServer.main(commandArguments);
        } else if ("visualizer".equals(command)) {
            RaptorDebugger.main(commandArguments);
        } else {
            System.err.println("Unknown command " + command);
        }
    }
}
