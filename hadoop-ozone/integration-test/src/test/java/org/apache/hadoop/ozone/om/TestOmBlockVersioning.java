/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership.  The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.hadoop.ozone.om;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.hadoop.hdds.client.StandaloneReplicationConfig;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.scm.container.common.helpers.ExcludeList;
import org.apache.hadoop.ozone.MiniOzoneCluster;
import org.apache.hadoop.ozone.TestDataUtil;
import org.apache.hadoop.ozone.client.OzoneBucket;
import org.apache.hadoop.ozone.om.helpers.OmKeyArgs;
import org.apache.hadoop.ozone.om.helpers.OmKeyInfo;
import org.apache.hadoop.ozone.om.helpers.OmKeyLocationInfo;
import org.apache.hadoop.ozone.om.helpers.OmKeyLocationInfoGroup;
import org.apache.hadoop.ozone.om.helpers.OpenKeySession;

import org.apache.commons.lang3.RandomStringUtils;
import org.junit.AfterClass;
import org.junit.Assert;

import static org.apache.hadoop.hdds.protocol.proto.HddsProtos.ReplicationFactor.ONE;
import static org.junit.Assert.assertEquals;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.Timeout;

/**
 * This class tests the versioning of blocks from OM side.
 */
public class TestOmBlockVersioning {

  /**
    * Set a timeout for each test.
    */
  @Rule
  public Timeout timeout = Timeout.seconds(300);
  private static MiniOzoneCluster cluster = null;
  private static OzoneConfiguration conf;
  private static OzoneManager ozoneManager;

  @Rule
  public ExpectedException exception = ExpectedException.none();

  /**
   * Create a MiniDFSCluster for testing.
   * <p>
   * Ozone is made active by setting OZONE_ENABLED = true
   *
   * @throws IOException
   */
  @BeforeClass
  public static void init() throws Exception {
    conf = new OzoneConfiguration();
    cluster = MiniOzoneCluster.newBuilder(conf).build();
    cluster.waitForClusterToBeReady();
    ozoneManager = cluster.getOzoneManager();
  }

  /**
   * Shutdown MiniDFSCluster.
   */
  @AfterClass
  public static void shutdown() {
    if (cluster != null) {
      cluster.shutdown();
    }
  }

  @Test
  public void testAllocateCommit() throws Exception {
    String volumeName = "volume" + RandomStringUtils.randomNumeric(5);
    String bucketName = "bucket" + RandomStringUtils.randomNumeric(5);
    String keyName = "key" + RandomStringUtils.randomNumeric(5);

    TestDataUtil.createVolumeAndBucket(cluster, volumeName, bucketName);

    OmKeyArgs keyArgs = new OmKeyArgs.Builder()
        .setVolumeName(volumeName)
        .setBucketName(bucketName)
        .setKeyName(keyName)
        .setDataSize(1000)
        .setRefreshPipeline(true)
        .setAcls(new ArrayList<>())
        .setReplicationConfig(new StandaloneReplicationConfig(ONE))
        .build();

    // 1st update, version 0
    OpenKeySession openKey = ozoneManager.openKey(keyArgs);
    // explicitly set the keyLocation list before committing the key.
    keyArgs.setLocationInfoList(openKey.getKeyInfo().getLatestVersionLocations()
        .getBlocksLatestVersionOnly());
    ozoneManager.commitKey(keyArgs, openKey.getId());

    OmKeyInfo keyInfo = ozoneManager.lookupKey(keyArgs);
    OmKeyLocationInfoGroup highestVersion =
        checkVersions(keyInfo.getKeyLocationVersions());
    assertEquals(0, highestVersion.getVersion());
    assertEquals(1, highestVersion.getLocationList().size());

    // 2nd update, version 1
    openKey = ozoneManager.openKey(keyArgs);
    //OmKeyLocationInfo locationInfo =
    //    ozoneManager.allocateBlock(keyArgs, openKey.getId());
    // explicitly set the keyLocation list before committing the key.
    keyArgs.setLocationInfoList(openKey.getKeyInfo().getLatestVersionLocations()
        .getBlocksLatestVersionOnly());
    ozoneManager.commitKey(keyArgs, openKey.getId());

    keyInfo = ozoneManager.lookupKey(keyArgs);
    highestVersion = checkVersions(keyInfo.getKeyLocationVersions());
    assertEquals(1, highestVersion.getVersion());
    assertEquals(1, highestVersion.getLocationList().size());

    // 3rd update, version 2
    openKey = ozoneManager.openKey(keyArgs);

    // this block will be appended to the latest version of version 2.
    OmKeyLocationInfo locationInfo =
        ozoneManager.allocateBlock(keyArgs, openKey.getId(),
            new ExcludeList());
    List<OmKeyLocationInfo> locationInfoList =
        openKey.getKeyInfo().getLatestVersionLocations()
            .getBlocksLatestVersionOnly();
    Assert.assertTrue(locationInfoList.size() == 1);
    locationInfoList.add(locationInfo);
    keyArgs.setLocationInfoList(locationInfoList);
    ozoneManager.commitKey(keyArgs, openKey.getId());

    keyInfo = ozoneManager.lookupKey(keyArgs);
    highestVersion = checkVersions(keyInfo.getKeyLocationVersions());
    assertEquals(2, highestVersion.getVersion());
    assertEquals(2, highestVersion.getLocationList().size());
  }

  private OmKeyLocationInfoGroup checkVersions(
      List<OmKeyLocationInfoGroup> versions) {
    OmKeyLocationInfoGroup currentVersion = null;
    for (OmKeyLocationInfoGroup version : versions) {
      if (currentVersion != null) {
        assertEquals(currentVersion.getVersion() + 1, version.getVersion());
      }
      currentVersion = version;
    }
    return currentVersion;
  }

  @Test
  public void testReadLatestVersion() throws Exception {

    String volumeName = "volume" + RandomStringUtils.randomNumeric(5);
    String bucketName = "bucket" + RandomStringUtils.randomNumeric(5);
    String keyName = "key" + RandomStringUtils.randomNumeric(5);

    OzoneBucket bucket =
        TestDataUtil.createVolumeAndBucket(cluster, volumeName, bucketName);

    OmKeyArgs omKeyArgs = new OmKeyArgs.Builder()
        .setVolumeName(volumeName)
        .setBucketName(bucketName)
        .setKeyName(keyName)
        .setDataSize(1000)
        .setRefreshPipeline(true)
        .build();

    String dataString = RandomStringUtils.randomAlphabetic(100);

    TestDataUtil.createKey(bucket, keyName, dataString);
    assertEquals(dataString, TestDataUtil.getKey(bucket, keyName));
    OmKeyInfo keyInfo = ozoneManager.lookupKey(omKeyArgs);
    assertEquals(0, keyInfo.getLatestVersionLocations().getVersion());
    assertEquals(1,
        keyInfo.getLatestVersionLocations().getLocationList().size());

    // this write will create 2nd version, 2nd version will contain block from
    // version 1, and add a new block
    TestDataUtil.createKey(bucket, keyName, dataString);


    keyInfo = ozoneManager.lookupKey(omKeyArgs);
    assertEquals(dataString, TestDataUtil.getKey(bucket, keyName));
    assertEquals(1, keyInfo.getLatestVersionLocations().getVersion());
    assertEquals(1,
        keyInfo.getLatestVersionLocations().getLocationList().size());

    dataString = RandomStringUtils.randomAlphabetic(200);
    TestDataUtil.createKey(bucket, keyName, dataString);

    keyInfo = ozoneManager.lookupKey(omKeyArgs);
    assertEquals(dataString, TestDataUtil.getKey(bucket, keyName));
    assertEquals(2, keyInfo.getLatestVersionLocations().getVersion());
    assertEquals(1,
        keyInfo.getLatestVersionLocations().getLocationList().size());
  }
}