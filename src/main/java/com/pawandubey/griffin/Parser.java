/*
 * Copyright 2015 Pawan Dubey pawandubey@outlook.com.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.pawandubey.griffin;

import com.github.rjeschke.txtmark.Configuration;
import com.github.rjeschke.txtmark.Processor;
import static com.pawandubey.griffin.Configurator.LINE_SEPARATOR;
import static com.pawandubey.griffin.Data.config;
import static com.pawandubey.griffin.Data.executorSet;
import static com.pawandubey.griffin.Data.tags;
import static com.pawandubey.griffin.DirectoryCrawler.OUTPUT_DIRECTORY;
import static com.pawandubey.griffin.DirectoryCrawler.SOURCE_DIRECTORY;
import static com.pawandubey.griffin.DirectoryCrawler.TAG_DIRECTORY;
import com.pawandubey.griffin.model.Parsable;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 *
 * @author Pawan Dubey pawandubey@outlook.com
 */
public class Parser {

    private final Configuration renderConfig;
    private final Renderer renderer;
    private String parsedContent;

    /**
     * creates a parser with configuration set to enable safe mode HTML with
     * extended profile from txtmark, allowing spaces in fenced code blocks and
     * encoding set to UTF-8.
     *
     * @throws java.io.IOException the exception
     */
    public Parser() throws IOException {
        renderer = new Renderer();
        renderConfig = Configuration.builder().enableSafeMode()
                .forceExtentedProfile()
                .setAllowSpacesInFencedCodeBlockDelimiters(true)
                .setEncoding("UTF-8")
                .setCodeBlockEmitter(new CodeBlockEmitter())
                .build();
    }

    /**
     * Parses the collection of files in the queue to produce HTML output
     *
     * @param collection the queue of files to be parsed
     * @throws InterruptedException the exception
     * @throws java.io.IOException the exception
     */
    protected void parse(BlockingQueue<Parsable> collection) throws InterruptedException, IOException {
        Parsable p;
        String content;
        if (config.getRenderTags()) {
            Files.createDirectory(Paths.get(TAG_DIRECTORY));
        }
        while (!collection.isEmpty()) {
            p = collection.take();
            writeParsedFile(p);
            renderTags();
        }
        if (Files.notExists(Paths.get(OUTPUT_DIRECTORY).resolve("index.html"))) {
            try (BufferedWriter bw = Files.newBufferedWriter(Paths.get(OUTPUT_DIRECTORY).resolve("index.html"), StandardCharsets.UTF_8)) {
                bw.write(renderer.renderIndex());
            }
            catch (IOException ex) {
                Logger.getLogger(Parser.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
    }

    //TODO Method doesnt fit on screen -> TOO LONG. Refactor.
    /**
     * Renders the posts of each tag as symbolic links. 
     * This method calcualtes the number of threads needed based on the criteria
     * that one thread will handle 10 tags. Then partitions the tags keyset into
     * that many Lists of tags. Then each list is processed by a new thread in
     * the executor service.
     *
     * @throws IOException the exception
     */
    protected void renderTags() throws IOException {
        if (config.getRenderTags()) {
            int numThreads = (tags.size() / 10) + 1;
            ExecutorService tagExecutor = Executors.newFixedThreadPool(numThreads);
            executorSet.add(tagExecutor);

            Set<List<String>> parts = new HashSet<>();
            for (int i = 0; i < numThreads; i++) {
                parts.add(tags.keySet()
                        .stream()
                        .skip(i * 10)
                        .limit(10)
                        .collect(Collectors.toList()));
            }

            parts.stream().forEach(p
                    -> {
                        tagExecutor.submit(() -> {
                            for (String t : p) {
                                Path tagDir = Paths.get(TAG_DIRECTORY).resolve(t);

                                if (Files.notExists(tagDir)) {
                                    try {
                                        Files.createDirectory(tagDir);
                                    }
                                    catch (IOException ex) {
                                        Logger.getLogger(Parser.class.getName()).log(Level.SEVERE, null, ex);
                                    }
                                }

                        for (Parsable pa : tags.get(t)) {
                            try {
                                if (Files.notExists(tagDir.resolve(pa.getSlug()))) {
                                    Files.createDirectory(tagDir.resolve(pa.getSlug()));
                                }
                                Path link = tagDir.resolve(pa.getSlug()).resolve("index.html");
                                Path htmlPath = resolveHtmlPath(pa);
                                if (Files.notExists(link, LinkOption.NOFOLLOW_LINKS)) {
                                    Files.createSymbolicLink(link, htmlPath);
                                }
                            }
                            catch (IOException ex) {
                                Logger.getLogger(Parser.class.getName()).log(Level.SEVERE, null, ex);
                            }
                        }
                    }
                });
                    });
        }
    }

    /**
     * Shuts down all executors in the executorSet one by one gracefully.
     */
    protected void shutDownExecutors() {
        for (ExecutorService e : executorSet) {
            try {
                e.shutdown();
                e.awaitTermination(2, TimeUnit.SECONDS);

            }
            catch (InterruptedException ex) {
                Logger.getLogger(Parser.class
                        .getName()).log(Level.WARNING, null, ex);
            }
            finally {
                if (!e.isTerminated()) {
                    e.shutdownNow();
                }
            }
        }
    }

    /**
     * Reads the content from the given path into a String object.
     *
     * @param p the path to the file
     * @return the String contents
     */
    private String readFile(Path p) {
        StringBuilder sb = new StringBuilder();
        String line;
        try (BufferedReader br = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            while ((line = br.readLine()) != null) {
                sb.append(line).append(LINE_SEPARATOR);

            }
        }
        catch (IOException ex) {
            Logger.getLogger(Parser.class
                    .getName()).log(Level.SEVERE, null, ex);
        }
        return sb.toString();
    }

    /**
     * Writes the content of the Parsable to the path resolved from the slug by
     * creating a directory from the given slug and then writing the contents
     * into the index.html file inside it for pretty links.
     *
     * @param p the Parsable instance
     */
    private void writeParsedFile(Parsable p) throws IOException {
        Path htmlPath = resolveHtmlPath(p);

        try (BufferedWriter bw = Files.newBufferedWriter(htmlPath, StandardCharsets.UTF_8)) {
            parsedContent = Processor.process(p.getContent(), renderConfig);
            p.setContent(parsedContent);
            bw.write(renderer.renderParsable(p));

        }
        catch (IOException ex) {
            Logger.getLogger(Parser.class
                    .getName()).log(Level.SEVERE, null, ex);
        }
        if (config.getRenderTags()) {
            resolveTags(p, htmlPath);
        }
    }

    private Path resolveHtmlPath(Parsable p) throws IOException {
        String name = p.getSlug();
        Path parsedDirParent = Paths.get(OUTPUT_DIRECTORY).resolve(Paths.get(SOURCE_DIRECTORY).relativize(p.getLocation().getParent()));
        Path parsedDir = parsedDirParent.resolve(name);
        if (Files.notExists(parsedDir)) {
            Files.createDirectory(parsedDir);
        }
        Path htmlPath = parsedDir.resolve("index.html");
        return htmlPath;
    }

    private void resolveTags(Parsable p, Path htmlPath) throws IOException {
        List<String> ptags = p.getTags();
        for (String t : ptags) {
            if (!t.equals("nav")) {
                if (tags.get(t) != null) {
                    tags.get(t).add(p);
                }
                else {
                    List<Parsable> l = new ArrayList<>();
                    l.add(p);
                    tags.put(t, l);
                }
            }
        }
    }

}
