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
package com.addthis.hydra.job.spawn.balancer;

import java.io.File;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.addthis.basis.test.SlowTest;
import com.addthis.basis.util.JitterClock;
import com.addthis.basis.util.LessFiles;

import com.addthis.codec.config.Configs;
import com.addthis.hydra.job.Job;
import com.addthis.hydra.job.JobParameter;
import com.addthis.hydra.job.JobState;
import com.addthis.hydra.job.JobTask;
import com.addthis.hydra.job.JobTaskMoveAssignment;
import com.addthis.hydra.job.JobTaskReplica;
import com.addthis.hydra.job.JobTaskState;
import com.addthis.hydra.job.entity.JobCommand;
import com.addthis.hydra.job.mq.HostCapacity;
import com.addthis.hydra.job.mq.HostState;
import com.addthis.hydra.job.mq.JobKey;
import com.addthis.hydra.job.spawn.HostManager;
import com.addthis.hydra.job.spawn.Spawn;
import com.addthis.hydra.job.spawn.SpawnMQ;
import com.addthis.hydra.minion.HostLocation;
import com.addthis.hydra.util.ZkCodecStartUtil;

import org.apache.zookeeper.CreateMode;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.Mockito;

import static com.addthis.hydra.job.IJob.DEFAULT_MINION_TYPE;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@Category(SlowTest.class)
public class SpawnBalancerTest extends ZkCodecStartUtil {

    private Spawn spawn;
    private HostManager hostManager;
    private SpawnBalancer bal;
    private long now = JitterClock.globalTime();
    private String tmpRoot;

    @Before
    public void setup() throws Exception {

        tmpRoot = LessFiles.createTempDir().toString();
        System.setProperty("SPAWN_DATA_DIR", tmpRoot + "/tmp/spawn/data");
        System.setProperty("SPAWN_LOG_DIR", tmpRoot + "/tmp/spawn/log/events");
        if (zkClient.checkExists().forPath("/minion/up") == null) {
            zkClient.create().creatingParentsIfNeeded().forPath("/minon/up");
        }
        if (zkClient.checkExists().forPath("/minion/dead") == null) {
            zkClient.create().creatingParentsIfNeeded().forPath("/minon/dead");
        }
        try {
            Thread.sleep(100);
            spawn = Configs.newDefault(Spawn.class);
            hostManager = spawn.hostManager;
            bal = spawn.getSpawnBalancer();
            spawn.setSpawnMQ(Mockito.mock(SpawnMQ.class));
        } catch (Exception ex) {
        }
    }

    @After
    public void clear() throws Exception {
        if (zkClient.checkExists().forPath("/minion/up") != null) {
            zkClient.delete().forPath("/minon/up");
        }
        if (zkClient.checkExists().forPath("/minion/dead") != null) {
            zkClient.delete().forPath("/minon/dead");
        }
        LessFiles.deleteDir(new File(tmpRoot));
        spawn.close();
    }

    @Test
    public void  testPurgeMisplacedTasks() throws Exception {
        // purgeMisplacedTasks should delete orphaned tasks from non-existent jobs
        // pruneTaskReassignments should not ignore delete task

        HostState host = installHostStateWithUUID("host", spawn, true, new HostLocation("", "", ""));
        HostState otherHost = installHostStateWithUUID("otherHost", spawn, true, new HostLocation("", "", ""));
        waitForAllUpHosts();
        Job job = createSpawnJob(spawn, 1, Arrays.asList("host"), now, 80_000_000_000L, 0);
        host.setStopped(simulateJobKeys(job));
        host.setMax(new HostCapacity(10, 10, 10, 100_000_000_000L));
        host.setUsed(new HostCapacity(10, 10, 10, 90_900_000_000L));
        spawn.getJobCommandManager().putEntity("foo", new JobCommand(), false);
        hostManager.updateHostState(host);
        bal.updateAggregateStatistics(hostManager.listHostStatus(null));

        // Delete the job
        spawn.deleteJob(job.getId());

        int optimizedDirsIndex = spawn.rebalanceHost("host").toString().indexOf("JobTaskMoveAssignment");
        assertTrue("should not fail to delete orphan task", optimizedDirsIndex != -1);
    }

    @Test
    public void saveLoadConfig() {
        SpawnBalancerConfig config = new SpawnBalancerConfig();
        config.setBytesMovedFullRebalance(123456);
        bal.setConfig(config);
        bal.saveConfigToDataStore();
        SpawnBalancerConfig loadedConfig = bal.loadConfigFromDataStore(null);
        assertNotNull("not null", loadedConfig);
        assertEquals("correct value saved/loaded", 123456, loadedConfig.getBytesMovedFullRebalance());
    }

    @Test
    public void replicaSuitabilityTest() throws Exception {
        List<HostState> hosts = new ArrayList<>();
        int numHosts = 8;
        for (int i = 0; i < numHosts; i++) {
            HostState nextHost = new HostState("id" + i);
            nextHost.setUsed(new HostCapacity(0, 0, 0, i));
            nextHost.setMax(new HostCapacity(0, 0, 0, 2 * numHosts));
            nextHost.setUp(true);
            hosts.add(nextHost);
        }
        bal.markRecentlyReplicatedTo(Arrays.asList(new JobTaskMoveAssignment(new JobKey(), "", "id4", false, false)));
        hosts = bal.sortHostsByDiskSpace(hosts);
        assertEquals("should get lightest host first", "id0", hosts.get(0).getHostUuid());
        assertEquals("should get heaviest host second to last", "id" + (numHosts - 1), hosts.get(numHosts - 2).getHostUuid());
        assertEquals("should get recently-replicated-to host last", "id4", hosts.get(numHosts - 1).getHostUuid());
    }

    /**
     * Test to check default behavior of removing replicas from the end of the replica list
     * when no HostLocation information is available
     */
    @Test
    public void removeReplicasWithoutHostLocationTest() throws Exception {
        int numLightHosts = 9;
        ArrayList<String> hostIDs = new ArrayList<>(numLightHosts);

        for (int i = 0; i < numLightHosts; i++) {
            String hostID = "light" + i;
            hostIDs.add(hostID);
            HostState lightHost = installHostStateWithUUID(hostID, spawn, true,
                                                           new HostLocation("", "", "" ));
            lightHost.setUsed(new HostCapacity(0, 0, 0, 20_000_000_000L));
            lightHost.setMax(new HostCapacity(0, 0, 0, 100_000_000_000L));
        }

        waitForAllUpHosts();

        int numReplicas = 5;
        Job job = createJobAndUpdateHosts(spawn, numLightHosts, hostIDs, now, 1000, 0);
        job.setReplicas(numReplicas);
        spawn.updateJob(job);

        // Should have 5 replicas per task
        for(JobTask task : job.getCopyOfTasks()) {
            assertTrue(task.getReplicas().size() == numReplicas);
        }

        job.setReplicas(2);
        spawn.updateJob(job);
        assertTrue("Job should not be in degraded state", job.getState() == JobState.IDLE);
        for(JobTask task : job.getCopyOfTasks()) {
            Collection<String> taskHosts = task.getAllTaskHosts();
            assertTrue("Should have 1 task and 2 replicas", taskHosts.size() == 3);
        }
    }

    /**
     * Test to ensure that task and replica locations remain AD-aware upon replica deletion/removal
     */
    @Test
    public void removeReplicasTest() throws Exception {
        int numLightHosts = 9;
        ArrayList<String> hostIDs = new ArrayList<>(numLightHosts);

        String[] dataCenterIds = {"a", "b", "c"};

        for (int i = 0; i < numLightHosts; i++) {
            String hostID = "light" + i;
            hostIDs.add(hostID);
            HostState lightHost = installHostStateWithUUID(hostID, spawn, true,
                                                           new HostLocation(dataCenterIds[i % dataCenterIds.length],
                                                                            "",
                                                                            "" ));
            lightHost.setUsed(new HostCapacity(0, 0, 0, 20_000_000_000L));
            lightHost.setMax(new HostCapacity(0, 0, 0, 100_000_000_000L));
        }

        waitForAllUpHosts();

        int numReplicas = 5;
        Job job = createJobAndUpdateHosts(spawn, numLightHosts, hostIDs, now, 1000, 0);
        job.setReplicas(numReplicas);
        spawn.updateJob(job);

        assertTrue("Job should not be in degraded state", job.getState() == JobState.IDLE);

        // Should have 5 replicas per task
        for(JobTask task : job.getCopyOfTasks()) {
            assertTrue(task.getReplicas().size() == numReplicas);
            Set<HostLocation> locations = task.getAllTaskHosts().stream()
                                              .map(k -> hostManager.getHostState(k).getHostLocation())
                                              .collect(Collectors.toSet());
            assertTrue("Replicas should be spread out across HostLocations", locations.size() ==
                                                                             hostManager.getHostLocationSummary().getMinCardinality(hostManager.getHostLocationSummary().getPriorityLevel()));
        }

        job.setReplicas(2);
        spawn.updateJob(job);
        assertTrue(job.getState() == JobState.IDLE);
        for(JobTask task : job.getCopyOfTasks()) {
            Collection<String> taskHosts = task.getAllTaskHosts();
            assertTrue(taskHosts.size() == 3);
            Set<HostLocation> locations = taskHosts.stream()
                                                   .map(host -> hostManager.getHostState(host).getHostLocation())
                                                   .collect(Collectors.toSet());
            assertTrue("At least one replica should be on a different HostLocation", locations.size() > 1);
        }
    }

    @Test
    public void replicaAllocationTest() throws Exception {
        // Suppose we allocate a job with N tasks to a cluster with N hosts, with one of the hosts near full disk.
        // Then we should put replicas on all the light hosts, but not the heavy one
        String fullHostID = "full";
        HostState fullHost = installHostStateWithUUID(fullHostID, spawn, true, new HostLocation("a", "", ""));
        fullHost.setUsed(new HostCapacity(0, 0, 0, 9998));
        fullHost.setMax(new HostCapacity(0, 0, 0, 10_000));

        int numLightHosts = 9;
        ArrayList<String> hostIDs = new ArrayList<>(numLightHosts + 1);
        hostIDs.add(fullHostID);

        String[] dataCenterIds = {"a", "b", "c"};

        for (int i = 0; i < numLightHosts; i++) {
            String hostID = "light" + i;
            hostIDs.add(hostID);
            HostState lightHost = installHostStateWithUUID(hostID, spawn, true,
                                                           new HostLocation(dataCenterIds[i % dataCenterIds.length],
                                                                            "",
                                                                            "" ));
            lightHost.setUsed(new HostCapacity(0, 0, 0, 20_000_000_000L));
            lightHost.setMax(new HostCapacity(0, 0, 0, 100_000_000_000L));
        }

        waitForAllUpHosts();

        int numReplicas = 5;
        Job job = createJobAndUpdateHosts(spawn, numLightHosts + 1, hostIDs, now, 1000, 0);
        job.setReplicas(numReplicas);
        Map<Integer, List<String>> assignments = bal.getAssignmentsForNewReplicas(job);
        HashSet<String> usedHosts = new HashSet<>(numLightHosts);
        for (List<String> targets : assignments.values()) {
            assertEquals("should make one replica per task", numReplicas, targets.size());
            assertTrue("should not put replicas on full host", !targets.contains(fullHostID));
            usedHosts.addAll(targets);
        }
        assertTrue("should use many light hosts", numLightHosts > .75 * usedHosts.size());

        for(JobTask task : job.getCopyOfTasks()) {
            List<String> replicaAssignments = assignments.get(task.getTaskID());
            assertTrue("Should not put replica on the same host as the task", !replicaAssignments.contains(task.getHostUUID()));
        }

        // at least one replica should be in a different AD
        for(JobTask task : job.getCopyOfTasks()) {
            List<String> replicaAssignments = assignments.get(task.getTaskID());
            HashSet<HostLocation> locations = new HashSet<>(replicaAssignments.size());
            for(String replica : replicaAssignments) {
                locations.add(hostManager.getHostState(replica).getHostLocation());
            }

            locations.remove(hostManager.getHostState(task.getHostUUID()).getHostLocation());
            assertTrue(!locations.isEmpty());
        }
    }

    @Test
    public void sortHostsTest() throws Exception {
        // Suppose we have a cluster with the following machines:
        //  - a host that is readOnly
        //  - a host that is down
        //  - a host that is dead
        //  - a live host that has no jobs
        //  - a live host that has one old task and one new
        //  - a live host that has two new tasks
        // sortHostsByLiveTasks should omit the first two and return the last three in the given order.

        String readOnlyHostID = "read_only_host";
        HostState readOnlyHost = installHostStateWithUUID(readOnlyHostID, spawn, true, false, 0, "default",
                                                          new HostLocation("", "", ""));

        String downHostID = "down_host";
        HostState downHost = installHostStateWithUUID(downHostID, spawn, false,
                                                      new HostLocation("", "", ""));

        String deadHostID = "dead_host";
        HostState deadHost = installHostStateWithUUID(deadHostID, spawn, false,
                                                      new HostLocation("", "", ""));
        deadHost.setDead(true);

        String emptyHostID = "empty_host";
        HostState emptyHost = installHostStateWithUUID(emptyHostID, spawn, true,
                                                       new HostLocation("", "", ""));

        String oneOldOneNewHostID = "1old1new";
        HostState oldNewHost = installHostStateWithUUID(oneOldOneNewHostID, spawn, true,
                                                        new HostLocation("", "", ""));
        String twoNewHostID = "2new";
        HostState twoNewHost = installHostStateWithUUID(twoNewHostID, spawn, true,
                                                        new HostLocation("", "", ""));

        try {
            Thread.sleep(1000);
        } catch(InterruptedException e) {

        }

        Job oldJob = createSpawnJob(spawn, 1, Arrays.asList(oneOldOneNewHostID), 0l, 1, 0);
        Job newJob1 = createSpawnJob(spawn, 1, Arrays.asList(oneOldOneNewHostID), now, 1, 0);
        oldNewHost.setStopped(simulateJobKeys(oldJob, newJob1));

        Job newJob2 = createSpawnJob(spawn, 1, Arrays.asList(twoNewHostID), now, 1, 0);
        Job newJob3 = createSpawnJob(spawn, 1, Arrays.asList(twoNewHostID), now, 1, 0);
        twoNewHost.setStopped(simulateJobKeys(newJob2, newJob3));

        List<HostState> hosts = Arrays.asList(downHost, deadHost, oldNewHost, twoNewHost, emptyHost);
        HostState[] desiredOrder = new HostState[]{emptyHost, oldNewHost, twoNewHost};
        // manually update active job ids which sortHostsByActiveTasks() depends on
        // in production this is done periodically via scheduled calls to SpawnBalancer.updateAggregateStatistics()
        bal.updateActiveJobIDs();
        List<HostState> sortedHosts = bal.sortHostsByActiveTasks(hosts);
        assertEquals("shouldn't include read only", false, sortedHosts.contains(readOnlyHost));
        assertEquals("shouldn't include down host", false, sortedHosts.contains(downHost));
        assertEquals("shouldn't include dead host", false, sortedHosts.contains(deadHost));
        assertEquals("three hosts should make it through the sort", 3, sortedHosts.size());
        assertArrayEquals("hosts should be in order [empty, old, new]", desiredOrder, sortedHosts.toArray());
    }

    @Test
    public void allocateTasksAcrossHostsTest() throws Exception {
        // If we assign a job with 3N tasks to 3 hosts, N tasks should go on each host.
        String firstHostUUID = "first";
        String secondHostUUID = "second";
        String thirdHostUUID = "third";
        installHostStateWithUUID(firstHostUUID, spawn, true, new HostLocation("a", "", ""));
        installHostStateWithUUID(secondHostUUID, spawn, true, new HostLocation("b", "", ""));
        installHostStateWithUUID(thirdHostUUID, spawn, true, new HostLocation("c", "", ""));

        waitForAllUpHosts();

        spawn.getJobCommandManager().putEntity("foo", new JobCommand(), false);
        int numTasks = 15;
        Job job = createJobAndUpdateHosts(spawn, numTasks, Arrays.asList(firstHostUUID, secondHostUUID, thirdHostUUID), now, 1, 0);
        assertEquals("should divide tasks evenly", numTasks / 3, numTasksOnHost(job, firstHostUUID));
        assertEquals("should divide tasks evenly", numTasks / 3, numTasksOnHost(job, secondHostUUID));
        assertEquals("should divide tasks evenly", numTasks / 3, numTasksOnHost(job, thirdHostUUID));
    }

    @Test
    public void multiHostJobReallocationTaskSelectionTest() throws Exception {
        // Suppose we put a 4-node job on a single heavily loaded host, and there are two light hosts available.
        // After a job reallocation, we want to move one tasks to each light host.
        String heavyHostUUID = "heavy";
        String lightHost1UUID = "light1";
        String lightHost2UUID = "light2";
        HostState heavyHost = installHostStateWithUUID(heavyHostUUID, spawn, true,
                                                       new HostLocation("a", "", ""));
        HostState lightHost1 = installHostStateWithUUID(lightHost1UUID, spawn, true,
                                                        new HostLocation("b", "", ""));
        HostState lightHost2 = installHostStateWithUUID(lightHost2UUID, spawn, true,
                                                        new HostLocation("c", "", ""));

        waitForAllUpHosts();

        List<HostState> hosts = Arrays.asList(heavyHost, lightHost1, lightHost2);
        Job smallJob = createJobAndUpdateHosts(spawn, 6, Arrays.asList(heavyHostUUID), now, 1000, 0);
        List<JobTaskMoveAssignment> assignments = bal.getAssignmentsForJobReallocation(smallJob, -1, hosts);
        assertEquals("should move 4 tasks total for small job", 4, assignments.size());
        List<String> assignedHosts = new ArrayList<>();
        for (JobTaskMoveAssignment assignment : assignments) {
            assignedHosts.add(assignment.getTargetUUID());
        }
        Collections.sort(assignedHosts);
        assertArrayEquals("should move two tasks to each host",
                          new String[]{lightHost1UUID, lightHost1UUID, lightHost2UUID, lightHost2UUID}, assignedHosts.toArray());

        Job job2 = createJobAndUpdateHosts(spawn, 6, Arrays.asList(heavyHostUUID, lightHost1UUID, lightHost2UUID), now, 1000, 0);
        String brandNewHostUUID = "brandnew";
        HostState brandNewHost = installHostStateWithUUID(brandNewHostUUID, spawn, true,
                                                          new HostLocation("a", "", ""));
        waitForAllUpHosts();
        List<HostState> newHosts = Arrays.asList(heavyHost, lightHost1, lightHost2, brandNewHost);
        bal.updateAggregateStatistics(newHosts);
        List<JobTaskMoveAssignment> assignments2 = bal.getAssignmentsForJobReallocation(job2, -1, newHosts);
        assertEquals("should move one task", 1, assignments2.size());
        assertEquals("should move a task to the new host", brandNewHostUUID, assignments2.get(0).getTargetUUID());
    }

    @Test
    public void assignMoreTasksToLighterHostsTest() throws Exception {
        // Suppose we have a cluster with three hosts, one with a mild load.
        // If we create a 5-node job, we want to assign two tasks to each light host and one to the heavier host
        String heavyHostUUID = "heavy";
        String lightHost1UUID = "light1";
        String lightHost2UUID = "light2";
        HostState heavyHost = installHostStateWithUUID(heavyHostUUID, spawn, true, new HostLocation("", "", ""));
        HostState lightHost1 = installHostStateWithUUID(lightHost1UUID, spawn, true, new HostLocation("", "", ""));
        HostState lightHost2 = installHostStateWithUUID(lightHost2UUID, spawn, true, new HostLocation("", "", ""));
        waitForAllUpHosts();
        Job weightJob = createJobAndUpdateHosts(spawn, 10, Arrays.asList(heavyHostUUID), now, 1000, 0);
        bal.updateAggregateStatistics(hostManager.listHostStatus(null));
        Job otherJob = createJobAndUpdateHosts(spawn, 5, Arrays.asList(heavyHostUUID, lightHost1UUID, lightHost2UUID), now, 500, 0);
        assertEquals("should put two tasks on lightHost1", 2, numTasksOnHost(otherJob, lightHost1UUID));
        assertEquals("should put two tasks on lightHost2", 2, numTasksOnHost(otherJob, lightHost2UUID));
        assertEquals("should put one task on heavyHost", 1, numTasksOnHost(otherJob, heavyHostUUID));
    }

    @Test
    public void multiJobHostReallocationTaskSelectionTest() throws Exception {
        // Suppose we have two 3-node jobs all running on a single heavy host.
        // After a host reallocation, we want to move one task from each job to a light host.
        String heavyHostUUID = "heavy";
        String lightHostUUID = "light";
        HostState heavyHost = installHostStateWithUUID(heavyHostUUID, spawn, true, new HostLocation("a", "", ""));
        HostState lightHost = installHostStateWithUUID(lightHostUUID, spawn, true, new HostLocation("b", "", ""));
        waitForAllUpHosts();

        spawn.getJobCommandManager().putEntity("foo", new JobCommand(), false);
        int numTasks = 3;
        Job job1 = createJobAndUpdateHosts(spawn, numTasks, Arrays.asList(heavyHostUUID), now, 1, 0);
        Job job2 = createJobAndUpdateHosts(spawn, numTasks, Arrays.asList(heavyHostUUID), now, 1, 0);
        bal.updateAggregateStatistics(hostManager.listHostStatus(null));
        List<HostState> hosts = Arrays.asList(heavyHost, lightHost);
        List<JobTaskMoveAssignment> heavyHostTaskAssignments = bal.getAssignmentsToBalanceHost(heavyHost, hosts);
        assertEquals("should move 2 tasks", 2, heavyHostTaskAssignments.size());
        for (JobTaskMoveAssignment assignment : heavyHostTaskAssignments) {
            assertEquals("should move task from heavy host", heavyHostUUID, assignment.getSourceUUID());
            assertEquals("should move task to light host", lightHostUUID, assignment.getTargetUUID());
        }
        bal.clearRecentlyRebalancedHosts();
        List<JobTaskMoveAssignment> lightHostTaskAssignments = bal.getAssignmentsToBalanceHost(lightHost, hosts);
        assertEquals("should move 2 tasks", 2, lightHostTaskAssignments.size());
        for (JobTaskMoveAssignment assignment : lightHostTaskAssignments) {
            assertEquals("should move task from heavy host", heavyHostUUID, assignment.getSourceUUID());
            assertEquals("should move task to light host", lightHostUUID, assignment.getTargetUUID());
        }
    }

    @Test
    public void diskSpaceBalancingTest() throws Exception {
        String heavyHost1UUID = "heavy1";
        HostState heavyHost1 = installHostStateWithUUID(heavyHost1UUID, spawn, true, new HostLocation("a", "", ""));
        String heavyHost2UUID = "heavy2";
        HostState heavyHost2 = installHostStateWithUUID(heavyHost2UUID, spawn, true, new HostLocation("b", "", ""));

        waitForAllUpHosts();

        Job gargantuanJob = createSpawnJob(spawn, 1, Arrays.asList(heavyHost1UUID), now, 80_000_000_000L, 0);
        Job movableJob1 = createSpawnJob(spawn, 1, Arrays.asList(heavyHost1UUID), now, 820_000_000L, 0);
        heavyHost1.setStopped(simulateJobKeys(gargantuanJob, movableJob1));
        Job movableJob2 = createSpawnJob(spawn, 1, Arrays.asList(heavyHost2UUID), now, 820_000_000L, 0);
        Job movableJob3 = createSpawnJob(spawn, 1, Arrays.asList(heavyHost2UUID), now, 850_000_000L, 0);
        heavyHost2.setStopped(simulateJobKeys(movableJob2, movableJob3));
        Job movableJob4 = createSpawnJob(spawn, 2, Arrays.asList(heavyHost1UUID, heavyHost2UUID), now, 850_000_000L, 0);
        // Add job keys for tasks of movableJob4 to the task's assigned host
        List<JobTask> jobTasks = movableJob4.getCopyOfTasks();
        Integer i = 0;
        for(JobTask jobTask : jobTasks) {
            JobKey[] jobKeys = hostManager.getHostState(jobTask.getHostUUID()).getStopped();
            JobKey[] newKeys = Arrays.copyOf(jobKeys, jobKeys.length + 1);
            newKeys[jobKeys.length] = new JobKey(jobTask.getJobUUID(), i++);
            hostManager.getHostState(jobTask.getHostUUID()).setStopped(newKeys);
        }

        heavyHost1.setMax(new HostCapacity(10, 10, 10, 100_000_000_000L));
        heavyHost1.setUsed(new HostCapacity(10, 10, 10, 90_900_000_000L));
        heavyHost2.setMax(new HostCapacity(10, 10, 10, 100_000_000_000L));
        heavyHost2.setUsed(new HostCapacity(10, 10, 10, 90_900_000_000L));

        String lightHost1UUID = "light1";
        HostState lightHost1 = installHostStateWithUUID(lightHost1UUID, spawn, true, new HostLocation("a", "", ""));
        lightHost1.setMax(new HostCapacity(10, 10, 10, 100_000_000_000L));
        lightHost1.setUsed(new HostCapacity(10, 10, 10, 200_000_0000L));

        String lightHost2UUID = "light2";
        HostState lightHost2 = installHostStateWithUUID(lightHost2UUID, spawn, true, new HostLocation("b", "", ""));
        lightHost2.setMax(new HostCapacity(10, 10, 10, 100_000_000_000L));
        lightHost2.setUsed(new HostCapacity(10, 10, 10, 200_000_000L));

        String readOnlyHostUUID = "readOnlyHost";
        HostState readOnlyHost = installHostStateWithUUID(readOnlyHostUUID, spawn, true, true, 0, "default",
                                                          new HostLocation("b", "", ""));
        readOnlyHost.setMax(new HostCapacity(10, 10, 10, 100_000_000_000L));
        readOnlyHost.setUsed(new HostCapacity(10, 10, 10, 200_000_000L));
        List<HostState> hostsForOverUtilizedTest = Arrays.asList(heavyHost1, lightHost1, lightHost2, readOnlyHost);
        List<HostState> hostsForUnderUtilizedTest = Arrays.asList(heavyHost1, heavyHost2, lightHost1, readOnlyHost);

        spawn.getJobCommandManager().putEntity("foo", new JobCommand(), false);
        hostManager.updateHostState(heavyHost1);
        hostManager.updateHostState(heavyHost2);
        hostManager.updateHostState(lightHost1);
        hostManager.updateHostState(lightHost2);
        hostManager.updateHostState(readOnlyHost);

        waitForAllUpHosts();

        bal.updateAggregateStatistics(hostManager.listHostStatus(null));

        // Suppose we have one host with full disk and another with mostly empty disk.
        // Should move some tasks from heavy to light, but not too much.
        // Test that rebalance moved tasks off a heavy host to the light hosts
        List<JobTaskMoveAssignment> assignments = bal.getAssignmentsToBalanceHost(heavyHost1, hostsForOverUtilizedTest);
        long bytesMoved = 0;
        for (JobTaskMoveAssignment assignment : assignments) {
            bytesMoved += bal.getTaskTrueSize(spawn.getTask(assignment.getJobKey()));
            assertTrue("shouldn't move gargantuan task", !assignment.getJobKey().getJobUuid().equals(gargantuanJob.getId()));
            assertTrue("shouldn't move to read-only host", !(assignment.getTargetUUID().equals(readOnlyHostUUID)));
        }
        assertTrue("should move something", !assignments.isEmpty());
        assertTrue("should not move too much", bytesMoved <= bal.getConfig().getBytesMovedFullRebalance());

        // Clear recently balanced hosts
        bal.clearRecentlyRebalancedHosts();

        // Suppose we have one host with under-utilized disk and other hosts with overloaded disks.
        // Should move some tasks from heavy to light, but not too much.
        // Test that rebalance moved tasks on to a light host from heavy hosts
        assignments = bal.getAssignmentsToBalanceHost(lightHost1, hostsForUnderUtilizedTest);
        bytesMoved = 0;

        for (JobTaskMoveAssignment assignment : assignments) {
            bytesMoved += bal.getTaskTrueSize(spawn.getTask(assignment.getJobKey()));
            assertTrue("shouldn't move gargantuan task",
                       !assignment.getJobKey().getJobUuid().equals(gargantuanJob.getId()));
            assertTrue("shouldn't move from read-only host", !(assignment.getSourceUUID().equals(readOnlyHostUUID)));
        }

        assertTrue("should move something", !assignments.isEmpty());
        assertTrue("should not move too much", bytesMoved <= bal.getConfig().getBytesMovedFullRebalance());
    }


    @Test
    public void dontDoPointlessMovesTest() throws Exception {
        // Suppose we have a cluster that is essentially balanced. Rebalancing it shouldn't do anything.
        int numHosts = 3;
        List<HostState> hosts = new ArrayList<>(numHosts);
        List<String> hostNames = new ArrayList<>(numHosts);

        String[] dataCenterIds = {"a", "b", "c"};

        for (int i = 0; i < numHosts; i++) {
            String hostName = "host" + i;
            hostNames.add(hostName);
            HostState host = installHostStateWithUUID(hostName, spawn, true,
                                                      new HostLocation(dataCenterIds[i%dataCenterIds.length], "", ""));
            hosts.add(host);

        }
        waitForAllUpHosts();
        Job job1 = createJobAndUpdateHosts(spawn, numHosts, hostNames, now, 1000, 0);
        Job job2 = createJobAndUpdateHosts(spawn, numHosts - 1, hostNames, now, 1000, 0);
        for (HostState host : hosts) {
            assertEquals("shouldn't move anything for " + host.getHostUuid(), 0,
                         bal.getAssignmentsToBalanceHost(host, hosts).size());
        }
        assertEquals("shouldn't move anything for " + job1.getId(), 0,
                     bal.getAssignmentsForJobReallocation(job1, -1, hosts).size());
        assertEquals("shouldn't move anything for " + job2.getId(), 0,
                     bal.getAssignmentsForJobReallocation(job2, -1, hosts).size());

    }

    @Test
    public void kickOnSuitableHosts() throws Exception {
        String availHostID = "host2";
        HostState avail = installHostStateWithUUID(availHostID, spawn, true, false, 1, "default",
                                                   new HostLocation("", "", ""));
        waitForAllUpHosts();
        assertTrue("available host should be able to run task", avail.canMirrorTasks());
    }

    @Ignore("Always fails")
    @Test
    public void queuePersist() throws Exception {
        spawn.getJobCommandManager().putEntity("foo", new JobCommand(), false);
        spawn.getSystemManager().quiesceCluster(true, "unknown");
        installHostStateWithUUID("host", spawn, true, new HostLocation("", "", ""));
        Job job = createJobAndUpdateHosts(spawn, 4, Arrays.asList("host"), now, 1000, 0);
        JobKey myKey = new JobKey(job.getId(), 0);
        spawn.addToTaskQueue(myKey, 0, false);
        spawn.writeSpawnQueue();
        // FIXME spawn2 can't be instantiated due to 5050 already being used by spawn
        try (Spawn spawn2 = Configs.newDefault(Spawn.class)) {
            spawn2.getSystemManager().quiesceCluster(true, "unknown");
            assertEquals("should have one queued task", 1, spawn.getTaskQueuedCount());
        }
    }

    @Test
    public void multipleMinionsPerHostReplicaTest() throws Exception {
        bal.getConfig().setAllowSameHostReplica(true);
        HostState host1m1 = installHostStateWithUUID("m1", spawn, true,
                                                     new HostLocation("a", "", ""));
        host1m1.setHost("h1");
        hostManager.updateHostState(host1m1);
        HostState host1m2 = installHostStateWithUUID("m2", spawn, true,
                                                     new HostLocation("a", "", ""));
        host1m2.setHost("h1");
        hostManager.updateHostState(host1m2);
        waitForAllUpHosts();
        Job job = createJobAndUpdateHosts(spawn, 1, Arrays.asList("m1", "m2"), now, 1000, 0);
        job.setReplicas(1);
        spawn.updateJob(job);
        assertEquals("should get one replica when we allow same host replicas", 1, spawn.getTask(job.getId(), 0).getAllReplicas().size(), 1);
        bal.getConfig().setAllowSameHostReplica(false);
        spawn.updateJob(job);
        assertEquals("should get no replicas when we disallow same host replicas", 0, spawn.getTask(job.getId(), 0).getAllReplicas().size());
    }

    @Test
    public void rebalanceOntoNewHostsTest() throws Exception {
        // Suppose we start out with eight hosts, and have a job with 10 live tasks.
        // Then if we add two hosts and rebalance the job, we should move tasks onto each.
        spawn.setSpawnMQ(Mockito.mock(SpawnMQ.class));
        bal.getConfig().setAllowSameHostReplica(true);
        ArrayList<String> hosts = new ArrayList<>();
        String[] dataCenterIds = {"a", "b"};
        for (int i = 0; i < 8; i++) {
            installHostStateWithUUID("h" + i, spawn, true, new HostLocation(dataCenterIds[i%dataCenterIds.length], "", ""));
            hosts.add("h" + i);
        }
        waitForAllUpHosts();

        Job myJob = createJobAndUpdateHosts(spawn, 20, hosts, JitterClock.globalTime(), 2000L, 0);
        installHostStateWithUUID("hNEW1", spawn, true, new HostLocation("a", "", ""));
        installHostStateWithUUID("hNEW2", spawn, true, new HostLocation("b", "", ""));

        waitForAllUpHosts();

        List<HostState> hostStates = hostManager.listHostStatus(null);
        bal.updateAggregateStatistics(hostStates);
        List<JobTaskMoveAssignment> assignments = bal.getAssignmentsForJobReallocation(myJob, -1, hostStates);
        int h1count = 0;
        int h2count = 0;
        for (JobTaskMoveAssignment assignment : assignments) {
            if (assignment.getTargetUUID().equals("hNEW1")) {
                h1count++;
            } else if (assignment.getTargetUUID().equals("hNEW2")) {
                h2count++;
            } else {
                throw new RuntimeException("should not push anything onto host " + assignment.getTargetUUID());
            }
        }
        assertTrue("should move tasks onto first new host", h1count > 1);
        assertTrue("should move tasks onto second new host", h2count > 1);

    }

    @Test
    public void hostScoreTest() throws Exception {
        // Test that heavily-loaded and lightly-loaded hosts are identified as such, and that medium-loaded hosts are
        // not identified as heavy or light
        long[] used = new long[]{1_000_000_000L, 99_000_000_000L, 100_000_000_000L, 105_000_000_000L, 200_000_000_000L};
        int i = 0;
        for (long usedVal : used) {
            HostState hostState = installHostStateWithUUID("host" + (i++), spawn, true, new HostLocation("", "", ""));
            hostState.setUsed(new HostCapacity(0, 0, 0, usedVal));
            hostState.setMax(new HostCapacity(0, 0, 0, 250_000_000_000L));
        }
        bal.updateAggregateStatistics(hostManager.listHostStatus(null));
        assertTrue("should correctly identify light host", bal.isExtremeHost("host0", true, false));
        assertTrue("should not identify light host as heavy", !bal.isExtremeHost("host0", true, true));
        assertTrue("should not identify medium host as light", !bal.isExtremeHost("host1", true, false));
        assertTrue("should not identify medium host as heavy", !bal.isExtremeHost("host1", true, true));
        assertTrue("should correctly identify heavy host", bal.isExtremeHost("host4", true, true));
        assertTrue("should not identify heavy host as light", !bal.isExtremeHost("host4", true, false));
    }

    @Test
    public void jobStateChangeTest() throws Exception
    {
        // Simulate some task state changes, and make sure job.isFinished() behaves as expected.
        List<String> hosts = Arrays.asList("h1", "h2", "h3");
        for (String host : hosts)
        {
            installHostStateWithUUID(host, spawn, true, new HostLocation("", "", ""));
        }
        waitForAllUpHosts();
        spawn.getJobCommandManager().putEntity("a", new JobCommand(), true);
        Job job = spawn.createJob("fsm", 3, hosts, "default", "a", false);
        JobTask task0 = job.getTask(0);
        JobTask task1 = job.getTask(1);
        job.setTaskState(task0, JobTaskState.BUSY);
        // If a task is busy, the job should not be finished
        assertTrue("job should not be finished", !job.isFinished());
        job.setTaskState(task0, JobTaskState.IDLE);
        assertTrue("job should be finished", job.isFinished());
        job.setTaskState(task1, JobTaskState.MIGRATING, true);
        // If a task is migrating, the job should not be finished.
        assertTrue("job should not be finished", !job.isFinished());
        job.setTaskState(task1, JobTaskState.IDLE, true);
        job.setTaskState(task0, JobTaskState.REBALANCE);
        // If a task is rebalancing, the job _should_ be finished.
        // The idea is that the task successfully ran, got into idle state, then a rebalance action was called afterwards.
        assertTrue("job should be finished", job.isFinished());
    }

    @Test
    public void jobDependencyTest() throws Exception {
        installHostStateWithUUID("a", spawn, true, new HostLocation("", "", ""));
        installHostStateWithUUID("b", spawn, true, new HostLocation("", "", ""));
        waitForAllUpHosts();
        Job sourceJob = createSpawnJob(spawn, 1, Arrays.asList("a", "b"), 1l, 1l, 0);
        Job downstreamJob = createSpawnJob(spawn, 1, Arrays.asList("a", "b"), 1l, 1l, 0);
        downstreamJob.setParameters(Arrays.asList(new JobParameter("param", sourceJob.getId(), "DEFAULT")));
        spawn.updateJob(downstreamJob);
        assertEquals("dependency graph should have two nodes", 2, spawn.getJobDependencies().getNodes().size());
    }

    @Test
    public  void moveAssignmentListTest() throws Exception {
        MoveAssignmentList moveAssignmentList = new MoveAssignmentList(spawn, new SpawnBalancerTaskSizer(spawn, hostManager));
        // Add a live task
        boolean addTask = moveAssignmentList.add(new JobTaskMoveAssignment(new JobKey("job1", 0), "srcHost", "destHost", false, false));
        assertTrue("should add live", addTask);
        // Try to add a replica of the live task
        addTask = moveAssignmentList.add(new JobTaskMoveAssignment(new JobKey("job1", 0), "srcHost", "destHost", true, false));
        assertTrue("should not add replica", !addTask);
    }

    @Test
    public void testHostCandidateIterator() throws Exception {
        String hostId1 = "hostId1";
        HostState hostState1 = installHostStateWithUUID(hostId1, spawn, true, new HostLocation("a", "aa", "aaa"));
        String hostId2 = "hostId2";
        HostState hostState2 = installHostStateWithUUID(hostId2, spawn, true, new HostLocation("b", "bb", "bbb"));
        String hostId3 = "hostId3";
        HostState hostState3 = installHostStateWithUUID(hostId3, spawn, true, new HostLocation("a", "aa", "aab"));
        String hostId4 = "hostId4";
        HostState hostState4 = installHostStateWithUUID(hostId4, spawn, true, new HostLocation("a", "ab", "aba"));
        String hostId5 = "hostId5";
        HostState hostState5 = installHostStateWithUUID(hostId5, spawn, true, new HostLocation("a", "ab", "aba"));
        String hostId6 = "hostId6";
        HostState hostState6 = installHostStateWithUUID(hostId6, spawn, true, new HostLocation("c", "cc", "ccc"));
        Job job = createSpawnJob(spawn, 1, Arrays.asList(hostId1), now, 80_000_000L, 0);
        hostState1.setStopped( simulateJobKeys(job));

        hostState1.setMax(new HostCapacity(10, 10, 10, 100_000_000_000L));
        hostState2.setMax(new HostCapacity(10, 10, 10, 100_000_000_000L));
        hostState3.setMax(new HostCapacity(10, 10, 10, 100_000_000_000L));
        hostState4.setMax(new HostCapacity(10, 10, 10, 100_000_000_000L));
        hostState5.setMax(new HostCapacity(10, 10, 10, 100_000_000_000L));
        hostState6.setMax(new HostCapacity(10, 10, 10, 100_000_000_000L));

        hostState1.setUsed(new HostCapacity(10, 10, 10, 200L));
        hostState2.setUsed(new HostCapacity(10, 10, 10, 500L));
        hostState3.setUsed(new HostCapacity(10, 10, 10, 500L));
        hostState4.setUsed(new HostCapacity(10, 10, 10, 300L));
        hostState5.setUsed(new HostCapacity(10, 10, 10, 100L));
        hostState6.setUsed(new HostCapacity(10, 10, 10, 200L));

        waitForAllUpHosts();
        bal.updateAggregateStatistics(hostManager.listHostStatus(null));

        for(JobTask task : job.getCopyOfTasks()) {
            // Use a dummy value of 25 for taskScoreIncrement
            HostCandidateIterator hostCandidateIterator =
                    new HostCandidateIterator(spawn, job.getCopyOfTasks(),
                                              bal.generateHostStateScoreMap(hostManager.listHostStatus(job.getMinionType())));
            List<String> hostIdsToAdd = hostCandidateIterator.getNewReplicaHosts(5, task);
            assertTrue("Host candidate iterator should have hosts", !hostIdsToAdd.isEmpty());

            Iterator<String> iterator = hostIdsToAdd.iterator();
            assertTrue("Should choose HostLocation with min score and different datacenter",
                       iterator.next().equals("hostId6"));
            assertTrue("Should choose HostLocation on different datacenter next",
                       iterator.next().equals("hostId2"));
            assertTrue("Should choose Host with lower score on different rack next",
                       iterator.next().equals("hostId5"));
            assertTrue("Should choose Host on different physical host next",
                       iterator.next().equals("hostId3"));
            assertTrue("Should not choose Host in the same location if other hosts available",
                       iterator.next().equals("hostId2"));
        }
    }

    @Test
    public void isMoveSpreadOutForAdTest() throws Exception {
        HostState hostState1 = installHostStateWithUUID("host1", spawn, true, new HostLocation("a", "aa", "aaa"));
        HostState hostState2 = installHostStateWithUUID("host2", spawn, true, new HostLocation("b", "bb", "bbb"));
        HostState hostState3 = installHostStateWithUUID("host3", spawn, true, new HostLocation("a", "aa", "aab"));
        HostState hostState4 = installHostStateWithUUID("host4", spawn, true, new HostLocation("a", "ab", "aba"));
        HostState hostState5 = installHostStateWithUUID("host5", spawn, true, new HostLocation("a", "ab", "aba"));
        HostState hostState6 = installHostStateWithUUID("host6", spawn, true, new HostLocation("c", "cc", "ccc"));

        waitForAllUpHosts();

        hostState1.setMax(new HostCapacity(10, 10, 10, 100_000_000_000L));
        hostState2.setMax(new HostCapacity(10, 10, 10, 100_000_000_000L));
        hostState3.setMax(new HostCapacity(10, 10, 10, 100_000_000_000L));
        hostState4.setMax(new HostCapacity(10, 10, 10, 100_000_000_000L));
        hostState5.setMax(new HostCapacity(10, 10, 10, 100_000_000_000L));
        hostState6.setMax(new HostCapacity(10, 10, 10, 100_000_000_000L));

        hostState1.setUsed(new HostCapacity(10, 10, 10, 200L));
        hostState2.setUsed(new HostCapacity(10, 10, 10, 500L));
        hostState3.setUsed(new HostCapacity(10, 10, 10, 500L));
        hostState4.setUsed(new HostCapacity(10, 10, 10, 300L));
        hostState5.setUsed(new HostCapacity(10, 10, 10, 100L));
        hostState6.setUsed(new HostCapacity(10, 10, 10, 200L));

        bal.updateAggregateStatistics(hostManager.listHostStatus(null));

        Job job = createSpawnJob(spawn, 1, Arrays.asList("host1"), now, 80_000_000L, 0);
        hostState1.setStopped(simulateJobKeys(job));
        job.setReplicas(1);
        spawn.updateJob(job);

        JobTask task = job.getTask(0);
        HostLocation fromLocation = hostManager.getHostState(task.getHostUUID()).getHostLocation();
        assertTrue("Should be able to move to other minAD location",
                   bal.isTaskSpreadOutAcrossAd(fromLocation, hostState2.getHostLocation(), task));
        assertTrue("Should be able to move within the same HostLocation",
                   bal.isTaskSpreadOutAcrossAd(fromLocation, hostState3.getHostLocation(), task));
        assertTrue("Should be able to move within the same minAD HostLocation",
                   bal.isTaskSpreadOutAcrossAd(fromLocation, hostState4.getHostLocation(), task));
        assertTrue("Should be able to move within the same minAD HostLocation",
                   bal.isTaskSpreadOutAcrossAd(fromLocation, hostState5.getHostLocation(), task));
        assertTrue("Should not be able to move to replica location when other minAD location is available",
                    !bal.isTaskSpreadOutAcrossAd(fromLocation, hostState6.getHostLocation(), task));

    }

    private Job createSpawnJob(Spawn spawn, int numTasks, List<String> hosts, long startTime, long taskSizeBytes, int numReplicas) throws Exception {
        Job job = spawn.createJob("fsm", numTasks, hosts, DEFAULT_MINION_TYPE, "foo", false);
        job.setReplicas(numReplicas);
        for (JobTask task : job.getCopyOfTasks()) {
            task.setByteCount(taskSizeBytes);
        }
        job.setStartTime(startTime);
        return job;
    }

    private Job createJobAndUpdateHosts(Spawn spawn, int numTasks, List<String> hosts, long startTime, long taskSizeBytes, int numReplicas) throws Exception {
        Job job = createSpawnJob(spawn, numTasks, hosts, startTime, taskSizeBytes, numReplicas);
        spawn.updateJob(job);
        for (JobTask task : job.getCopyOfTasks()) {
            task.setFileCount(1L);
            HostState host = hostManager.getHostState(task.getHostUUID());
            host.setStopped(updateJobKeyArray(host.getStopped(), task.getJobKey()));
            host.setMeanActiveTasks(1);
            hostManager.updateHostState(host);
            if (task.getReplicas() != null) {
                for (JobTaskReplica replica : task.getReplicas()) {
                    HostState rHost = hostManager.getHostState(replica.getHostUUID());
                    rHost.setStopped(updateJobKeyArray(rHost.getStopped(), task.getJobKey()));
                    hostManager.updateHostState(host);
                }
            }
        }
        return job;
    }

    private JobKey[] updateJobKeyArray(JobKey[] keys, JobKey newKey) {
        List<JobKey> keyList = keys == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(keys));
        keyList.add(newKey);
        return keyList.toArray(new JobKey[]{});
    }

    private int numTasksOnHost(Job job, String hostID) {
        int count = 0;
        for (JobTask task : job.getCopyOfTasks()) {
            if (task.getHostUUID().equals(hostID)) count++;
        }
        return count;
    }

    private JobKey[] simulateJobKeys(Job... jobs) {
        ArrayList<JobKey> keyList = new ArrayList<>();
        JobKey[] sampleArray = {new JobKey("", 0)};
        for (Job job : jobs) {
            for (int i = 0; i < job.getCopyOfTasks().size(); i++) {
                keyList.add(new JobKey(job.getId(), i));
            }
        }
        return keyList.toArray(sampleArray);
    }

    private HostState installHostStateWithUUID(String hostUUID, Spawn spawn, boolean isUp, HostLocation location) throws Exception {
        return installHostStateWithUUID(hostUUID, spawn, isUp, false, 1, "default", location);
    }

    private HostState installHostStateWithUUID(String hostUUID, Spawn spawn, boolean isUp, boolean readOnly, int availableSlots, String minionType, HostLocation location) throws Exception {
        String zkPath = isUp ? "/minion/up/" + hostUUID : "/minion/dead/" + hostUUID;
        zkClient.create().withMode(CreateMode.EPHEMERAL).forPath(zkPath);
        HostState newHostState = new HostState(hostUUID);
        newHostState.setMax(new HostCapacity(10, 10, 10, 100_000_000_000L));
        newHostState.setUsed(new HostCapacity(0, 0, 0, 100));
        newHostState.setHost("hostname-for:" + hostUUID);
        newHostState.setUp(isUp);
        newHostState.setAvailableTaskSlots(availableSlots);
        newHostState.setMinionTypes(minionType);
        newHostState.setDiskReadOnly(readOnly);
        newHostState.setHostLocation(location);
        hostManager.updateHostState(newHostState);
        return newHostState;
    }

    private void waitForAllUpHosts() throws Exception {
        // Wait for all hosts to be up due to time needed to pick up zk minion/up change. That matters because
        // HostManager.listHostStatus may set HostState.up to false depending on zk minion/up data, which may
        // affect test results below
        boolean hostsAreUp = false;
        for (int i = 0; i < 50; i++) {
            if (spawn.hostManager.listHostStatus(null).stream().allMatch(host -> host.isUp())) {
                hostsAreUp = true;
                break;
            } else {
                Thread.sleep(1000);
            }
        }
        if (!hostsAreUp) {
            throw new RuntimeException("Failed to find hosts after waiting");
        }
    }
}
