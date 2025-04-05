package ru.baldenna;

import java.io.File;
import java.nio.file.Files;
import java.text.CharacterIterator;
import java.text.StringCharacterIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

public class Main {

    static AtomicLong filesFound = new AtomicLong();
    static AtomicLong parallelSkipped = new AtomicLong();
    static AtomicLong parallelAllowed = new AtomicLong();

    public static void main(String[] args) throws Exception {

        var onlyDirs = Arrays.asList(args).contains("--only-dirs");
        var parallel = Arrays.asList(args).contains("-parallel");
        var verbose = Arrays.asList(args).contains("--verbose");
        var maxDepth = 100;
        var threads = 1;
        for (int i = 0; i < Arrays.asList(args).size(); i++) {
            if (args[i].equals("-max-depth")) {
                maxDepth = Integer.parseInt(args[i + 1]);
            }
        }
        for (int i = 0; i < Arrays.asList(args).size(); i++) {
            if (args[i].equals("-parallel")) {
                threads = Integer.parseInt(args[i + 1]);
            }
        }

        var curDir = new File(".");

        try (ThreadPoolExecutor executorService = (ThreadPoolExecutor) Executors.newFixedThreadPool(threads)) {
            var startTime = System.currentTimeMillis();
            var currentDirResults = printFilesInDirectoryParallel(curDir, 0, onlyDirs, maxDepth, executorService, parallel);

            if (verbose) {
                System.out.println("Parallel skipped: " + parallelSkipped.get());
                System.out.println("Parallel allowed: " + parallelAllowed.get());
            }

            System.out.println(currentDirResults.content);
            System.out.println("Processing parallel time: " + (System.currentTimeMillis() - startTime) + "ms");
            long processingTimeSeconds = Math.min(((System.currentTimeMillis()) - startTime) / 1000, 1);
            System.out.println("Files per second: " + filesFound.get() / processingTimeSeconds);
            System.out.println("Files total: " + filesFound.get());

        }
    }

    public static FileSizeDetails printFilesInDirectoryParallel(File directory, int depth, boolean onlyDirs, int maxDepth,
                                                                ThreadPoolExecutor executorService, boolean parallel) throws Exception {
        AtomicLong directorySize = new AtomicLong(0L);
        try {
            StringBuilder printer = new StringBuilder();
            var fileDetails = new ArrayList<FileSizeDetails>();
            var dirFiles = directory.listFiles();
            if (dirFiles == null) {
                return new FileSizeDetails(directory.getName() + "*EMPTY", 0L);
            }
            var files = Arrays.stream(dirFiles).filter(file -> !file.isDirectory()).toList();
            var directories = Arrays.stream(dirFiles).filter(File::isDirectory).toList();

            var tasksResults = new ArrayList<Future<FileSizeDetails>>();
            for (int i = 0; i < directories.size(); i++) {
                var subDirectory = directories.get(i);
                FileSizeDetails nestedDir;
                if (parallel && executorService.getActiveCount() < executorService.getCorePoolSize()) {
                    parallelAllowed.incrementAndGet();
                    var dirTaskFuture = executorService.submit(() -> {
                                var nestedFolder = printFilesInDirectoryParallel(subDirectory, depth + 1, onlyDirs, maxDepth, executorService, parallel);
                                var curDirContent = printFileDetails(depth, subDirectory.getName(), nestedFolder.size) + nestedFolder.content;
                                return new FileSizeDetails(curDirContent, nestedFolder.size);
                            }
                    );
                    tasksResults.add(dirTaskFuture);
                } else {
                    parallelSkipped.incrementAndGet();
                    nestedDir = printFilesInDirectoryParallel(subDirectory, depth + 1, onlyDirs, maxDepth, executorService, parallel);

                    var curDirContent = printFileDetails(depth, subDirectory.getName(), nestedDir.size) + nestedDir.content;
                    fileDetails.add(new FileSizeDetails(curDirContent, nestedDir.size));
                }
            }

            tasksResults.forEach(task -> {
                try {
                    fileDetails.add(task.get());
                    if (task.isCancelled()) System.out.println("Cancelled");
                } catch (Exception e) {
                    System.out.println(e.getMessage());
                }
            });

            fileDetails.forEach(dirResult -> {
                directorySize.addAndGet(dirResult.size);
                if (filesFound.incrementAndGet() % 10000 == 0) {
                    System.out.print("Processed files " + filesFound.get() + ". Active threads " + Math.min(executorService.getActiveCount(), 1));
                }
            });

            for (int i = 0; i < files.size(); i++) {
                var curFileSize = 0L;
                var file = files.get(i);

                curFileSize = Files.size(file.toPath());
                var curFileDetails = printFileDetails(depth, file.getName(), curFileSize);

                if (!onlyDirs) {
                    fileDetails.add(new FileSizeDetails(curFileDetails, curFileSize));
                }

                if (filesFound.incrementAndGet() % 10000 == 0) {
                    System.out.print("Processed files " + filesFound.get() + ". Active threads " + executorService.getActiveCount());
                }
                directorySize.addAndGet(curFileSize);
            }

            fileDetails.sort(Comparator.comparing(FileSizeDetails::size).reversed());

            fileDetails.forEach(fileDetail -> {
                if (depth <= maxDepth) {
                    printer.append(fileDetail);
                }
            });
            return new FileSizeDetails(printer.toString(), directorySize.get());
        } catch (Exception e) {
            System.out.println(e.getMessage());
            return new FileSizeDetails(directory.getName() + "*ERROR", 0L);
        }
    }


    private static String printFileDetails(int depth, String filename, long fileSize) {
        return String.join("", Collections.nCopies(depth, "  ")) +
                filename +
                " " +
                humanReadableByteCountBin(fileSize) +
                "\n";
    }


    public static String humanReadableByteCountBin(long bytes) {
        long absB = bytes == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(bytes);
        if (absB < 1024) {
            return bytes + " B";
        }
        long value = absB;
        CharacterIterator ci = new StringCharacterIterator("KMGTPE");
        for (int i = 40; i >= 0 && absB > 0xfffccccccccccccL >> i; i -= 10) {
            value >>= 10;
            ci.next();
        }
        value *= Long.signum(bytes);
        return String.format("%.1f %ciB", value / 1024.0, ci.current());
    }

    public record FileSizeDetails(String content, Long size) implements Comparable<FileSizeDetails> {
        @Override
        public int compareTo(FileSizeDetails o) {
            return size.compareTo(o.size);
        }

        @Override
        public String toString() {
            return content;
        }
    }
}