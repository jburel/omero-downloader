/*
 * Copyright (C) 2016 University of Dundee & Open Microscopy Environment.
 * All rights reserved.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package org.openmicroscopy.client.downloader;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.Ordering;

import java.io.IOException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import omero.RLong;
import omero.RType;
import omero.ServerError;
import omero.api.IQueryPrx;
import omero.cmd.FoundChildren;
import omero.cmd.UsedFilesRequest;
import omero.cmd.UsedFilesResponse;
import omero.cmd.UsedFilesResponsePreFs;
import omero.gateway.Gateway;
import omero.gateway.LoginCredentials;
import omero.gateway.SecurityContext;
import omero.gateway.exception.DSOutOfServiceException;
import omero.gateway.model.ExperimenterData;
import omero.gateway.util.Requests;
import omero.log.Logger;
import omero.log.SimpleLogger;
import omero.model.Image;
import omero.model.Roi;
import omero.sys.Parameters;
import omero.sys.ParametersI;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.springframework.util.CollectionUtils;

/**
 * OMERO client for downloading data in bulk from the server.
 * @author m.t.b.carroll@dundee.ac.uk
 */
public class Download {

    private static final Logger LOGGER = new SimpleLogger();
    private static final Gateway GATEWAY = new Gateway(LOGGER);
    private static final Pattern TARGET_PATTERN = Pattern.compile("([A-Z][A-Za-z]*):(\\d+(,\\d+)*)");

    private static SecurityContext ctx;
    private static IQueryPrx iQuery = null;
    private static RequestManager requests;

    /**
     * Parse the command-line options.
     * Aborts with help message if warranted.
     * @param argv the command-line options
     * @return the parsed options
     */
    private static CommandLine parseOptions(String argv[]) {
        final Options options = new Options();
        options.addOption("s", "server", true, "OMERO server host name");
        options.addOption("p", "port", true, "OMERO server port number");
        options.addOption("u", "user", true, "OMERO username");
        options.addOption("w", "pass", true, "OMERO password");
        options.addOption("k", "key", true, "OMERO session key");
        options.addOption("b", "only-binary", false, "download only binary files");
        options.addOption("c", "only-companion", false, "download only companion files");
        options.addOption("f", "whole-fileset", false, "download whole fileset");
        options.addOption("d", "base", true, "base directory for download");
        options.addOption("h", "help", false, "help");

        Integer exitCode = null;
        CommandLine parsed = null;
        try {
            final CommandLineParser parser = new DefaultParser();
            parsed = parser.parse(options, argv);
            if (parsed.hasOption('h')) {
                exitCode = 1;
            }
        } catch (ParseException pe) {
            exitCode = 2;
        }
        if (exitCode != null) {
            final HelpFormatter formatter = new HelpFormatter();
            formatter.printHelp("download", options);
            System.exit(exitCode);
        }

        return parsed;
    }

    /**
     * Open the gateway to the OMERO server and connect to set the security context.
     * @param parsedOptions the command-line options
     */
    private static void openGateway(CommandLine parsedOptions) {
        String host = parsedOptions.getOptionValue('s');
        String port = parsedOptions.getOptionValue('p');
        String user = parsedOptions.getOptionValue('u');
        final String pass = parsedOptions.getOptionValue('w');
        final String key = parsedOptions.getOptionValue('k');

        if (host == null) {
            host = "localhost";
        }
        if (port == null) {
            port = "4064";
        }

        if (key == null) {
            if (user == null || pass == null) {
                System.err.println("must offer username and password or session key");
                System.exit(2);
            }
        } else {
            if (user != null || pass != null) {
                LOGGER.warn(null, "username and password ignored if session key is provided");
            }
            user = key;
        }

        final LoginCredentials credentials = new LoginCredentials(user, pass, host, Integer.parseInt(port));
        try {
            final ExperimenterData experimenter = GATEWAY.connect(credentials);
            ctx = new SecurityContext(experimenter.getGroupId());
        } catch (DSOutOfServiceException oose) {
            LOGGER.fatal(oose, "cannot log in to server");
            System.exit(3);
        }
        requests = new RequestManager(GATEWAY, ctx, 250);
    }

    /**
     * Perform a query and return the results. Abort if the query fails.
     * @param hql the Hibernate query string
     * @param parameters values for the named parameters of the query
     * @return the query results
     */
    private static Iterable<List<RType>> query(String hql, Parameters parameters) {
        try {
            return iQuery.projection(hql, parameters);
        } catch (ServerError se) {
            LOGGER.fatal(se, "cannot use query service");
            System.exit(3);
            return null;
        }
    }

    /**
     * Perform the download as instructed.
     * @param argv the command-line options
     */
    public static void main(String argv[]) {
        /* parse the command-line options and connect to the OMERO server */
        final CommandLine parsedOptions = parseOptions(argv);
        if (parsedOptions.hasOption('b') && parsedOptions.hasOption('c')) {
            System.err.println("cannot combine multiple 'only' options");
            System.exit(2);
        }
        openGateway(parsedOptions);

        try {
            iQuery = GATEWAY.getQueryService(ctx);
        } catch (DSOutOfServiceException oose) {
            LOGGER.fatal(oose, "cannot access query service");
            System.exit(3);
        }
        /* determine which objects are targeted */
        final Requests.FindChildrenBuilder finder = Requests.findChildren().childType(Image.class).stopBefore(Roi.class);
        final List<String> targetArgs = parsedOptions.getArgList();
        if (targetArgs.isEmpty()) {
            LOGGER.fatal(null, "no download targets specified");
            System.exit(2);
        }
        for (final String target : targetArgs) {
            final Matcher matcher = TARGET_PATTERN.matcher(target);
            if (matcher.matches()) {
                finder.target(matcher.group(1));
                final Iterable<String> ids = Splitter.on(',').split(matcher.group(2));
                for (final String id : ids) {
                    finder.id(Long.parseLong(id));
                }
            } else {
                System.err.println("cannot parse Target:ids argument: " + target);
                System.exit(2);
            }
        }

        /* find the images of those targets */
        final FoundChildren found = requests.submit("finding target images", finder.build(), FoundChildren.class);
        final List<Long> imageIds = found.children.get(ome.model.core.Image.class.getName());
        if (CollectionUtils.isEmpty(imageIds)) {
            LOGGER.fatal(null, "no images found");
            System.exit(3);
        }

        /* map the filesets of the targeted images */
        final RelationshipManager localRepo = new RelationshipManager();
        localRepo.assertWantImages(imageIds);
        System.out.print("mapping fileset of images " + Joiner.on(", ").join(Ordering.natural().sortedCopy(imageIds)) + "...");
        System.out.flush();
        for (final List<RType> result : query(
                "SELECT fileset.id, id FROM Image WHERE fileset IN (SELECT fileset FROM Image WHERE id IN (:ids))",
                new ParametersI().addIds(imageIds))) {
            final long filesetId = ((RLong) result.get(0)).getValue();
            final long imageId = ((RLong) result.get(1)).getValue();
            localRepo.assertFilesetHasImage(filesetId, imageId);
            if (parsedOptions.hasOption('f')) {
                localRepo.assertWantImage(imageId);
            }
        }
        System.out.println(" done");

        /* map the files of the targeted images */
        for (final Long imageId : localRepo.getWantedImages()) {
            if (localRepo.isFsImage(imageId)) {
                if (parsedOptions.hasOption('b') || parsedOptions.hasOption('c')) {
                    final UsedFilesResponse usedFiles = requests.submit("determining files used by image " + imageId,
                            new UsedFilesRequest(imageId), UsedFilesResponse.class);
                    if (!parsedOptions.hasOption('c')) {
                        localRepo.assertImageHasFiles(imageId, usedFiles.binaryFilesThisSeries);
                    }
                    if (!parsedOptions.hasOption('b')) {
                        localRepo.assertImageHasFiles(imageId, usedFiles.companionFilesThisSeries);
                    }
                } else {
                    System.out.print("determining files used by image " + imageId + "...");
                    System.out.flush();
                    for (final List<RType> result : query(
                            "SELECT originalFile.id FROM FilesetEntry WHERE fileset.id = :id",
                            new ParametersI().addId(imageId))) {
                        final long fileId = ((RLong) result.get(0)).getValue();
                        localRepo.assertImageHasFile(imageId, fileId);
                    }
                    System.out.println(" done");
                }
            } else {
                final UsedFilesResponsePreFs usedFiles = requests.submit("determining files used by image " + imageId,
                        new UsedFilesRequest(imageId), UsedFilesResponsePreFs.class);
                    if (!parsedOptions.hasOption('c')) {
                        localRepo.assertImageHasFiles(imageId, usedFiles.archivedFiles);
                    }
                    if (!parsedOptions.hasOption('b')) {
                        localRepo.assertImageHasFiles(imageId, usedFiles.companionFiles);
                    }
            }
        }

        FileManager files = null;
        try {
             files = new FileManager(GATEWAY.getSharedResources(ctx).repositories(), iQuery);
        } catch (DSOutOfServiceException oose) {
            LOGGER.fatal(oose, "cannot access shared resources");
            System.exit(3);
        } catch (ServerError se) {
            LOGGER.fatal(se, "cannot use shared resources");
            System.exit(3);
        }

        LocalPaths paths = null;
        try {
            if (parsedOptions.hasOption('d')) {
                paths = new LocalPaths(parsedOptions.getOptionValue('d'));
            } else {
                paths = new LocalPaths();
            }
        } catch (IOException ioe) {
            LOGGER.fatal(ioe, "cannot access base download directory");
            System.exit(3);
        }

        localRepo.ensureFilesetImageLinks(paths);

        for (final long fileId : localRepo.getWantedFiles()) {
            files.checkFile(fileId, paths);
        }

        /* all done with the server */
        GATEWAY.disconnect();
    }
}