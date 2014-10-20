/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.addthis.hydra.query;

import javax.annotation.Nonnull;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.addthis.meshy.MeshyServer;
import com.addthis.meshy.service.file.FileReference;

import com.google.common.cache.CacheLoader;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListenableFutureTask;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

class FileRefCacheLoader extends CacheLoader<String, Multimap<Integer, FileReference>> {
    private final MeshyServer meshy;

    /** thread pool for refreshing cache keys asynchronously */
    @Nonnull private final ExecutorService fileReferenceCacheReloader =
            new ThreadPoolExecutor(2, 5, 5000L, TimeUnit.MILLISECONDS,
                                   new LinkedBlockingQueue<>(),
                                   new ThreadFactoryBuilder().setDaemon(true)
                                                             .setNameFormat("fileReferenceCacheReloader-%d")
                                                             .build());

    public FileRefCacheLoader(MeshyServer meshy) {
        this.meshy = meshy;
    }

    @Override public Multimap<Integer, FileReference> load(String key) throws InterruptedException {
        return loadFileReferencesForJob(key);
    }

    @Override
    public ListenableFuture<Multimap<Integer, FileReference>> reload(String key,
                                                                     Multimap<Integer, FileReference> oldValue) {
        ListenableFutureTask<Multimap<Integer, FileReference>> task =
                ListenableFutureTask.create(() -> loadFileReferencesForJob(key));
        fileReferenceCacheReloader.submit(task);
        return task;
    }

    /**
     * Loads the file references for a given job.
     *
     * @param job - the UID of the job to get the FileReferences for
     * @return - a map of the 'best' file references for each task in the given job
     */
    @Nonnull
    private Multimap<Integer, FileReference> loadFileReferencesForJob(String job) throws InterruptedException {
        final long startTime = System.currentTimeMillis();
        MeshFileRefCache.fileReferenceFetches.inc();
        if (meshy.getChannelCount() == 0) {
            MeshFileRefCache.log.warn("[MeshQueryMaster] Error: there are no available mesh peers.");
            return ImmutableMultimap.of();
        }

        Multimap<Integer, FileReference> fileRefDataSet = getFileReferences(job, "*");
        MeshFileRefCache.log.trace("file reference details before filtering:\n {}", fileRefDataSet);
        fileRefDataSet = MeshFileRefCache.filterFileReferences(fileRefDataSet);
        MeshFileRefCache.log.trace("file reference details after filtering:\n{}", fileRefDataSet);
        long duration = System.currentTimeMillis() - startTime;
        MeshFileRefCache.log.debug("File reference retrieval time: {}", duration);
        MeshFileRefCache.fileReferenceFetchTimes.update(duration, TimeUnit.MILLISECONDS);
        return fileRefDataSet;
    }


    /**
     * Fetch the file references for a specified job/task combination
     *
     * @param job The job id to search for
     * @param task task to search for, or "*" for all
     */
    @Nonnull
    Multimap<Integer, FileReference> getFileReferences(String job, String task) throws InterruptedException {
        final String prefix = "*/" + job + "/" + task + "/gold/data/query";
        FileRefSource fileRefSource = new FileRefSource(meshy);
        fileRefSource.requestLocalFiles(prefix);
        Multimap<Integer, FileReference> fileRefMap = fileRefSource.getWithShortCircuit();
        MeshFileRefCache.log.debug("found: {} pairs", fileRefMap.keySet().size());
        return fileRefMap;
    }
}