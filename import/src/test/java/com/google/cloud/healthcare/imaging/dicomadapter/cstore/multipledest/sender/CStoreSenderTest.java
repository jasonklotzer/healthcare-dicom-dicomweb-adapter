package com.google.cloud.healthcare.imaging.dicomadapter.cstore.multipledest.sender;

import com.google.cloud.healthcare.IDicomWebClient;
import com.google.cloud.healthcare.LogUtil;
import com.google.cloud.healthcare.imaging.dicomadapter.*;
import com.google.cloud.healthcare.imaging.dicomadapter.cmove.ISender;
import com.google.cloud.healthcare.imaging.dicomadapter.cmove.ISenderFactory;
import com.google.cloud.healthcare.imaging.dicomadapter.util.DimseRSPAssert;
import com.google.cloud.healthcare.imaging.dicomadapter.util.PortUtil;
import com.google.cloud.healthcare.imaging.dicomadapter.util.StubCStoreService;
import com.google.common.io.CountingInputStream;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.DicomInputStream;
import org.dcm4che3.net.*;
import org.dcm4che3.net.pdu.AAssociateRQ;
import org.dcm4che3.net.pdu.PresentationContext;
import org.dcm4che3.net.service.BasicCEchoSCP;
import org.dcm4che3.net.service.DicomService;
import org.dcm4che3.net.service.DicomServiceRegistry;
import org.json.JSONArray;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;

@RunWith(JUnit4.class)
public class CStoreSenderTest {
  // Server properties.
  private final static String SERVER_AET = "SERVER";
  private final static String SERVER_HOST_NAME = "localhost";
  private final static String CLIENT_AET = "CLIENT";
  private final static String MOVE_DESTINATION_HOSTNAME = "localhost";
  private final static String SOP_INSTANCE_ID = "1.2.840.113543.6.6.1.1.2.2415947926921624359.201235357587280";
  private final static String PATH_TO_DCM_FILE = "..\\integration_test\\data\\" + SOP_INSTANCE_ID;

  // Client properties.
  private ApplicationEntity clientAE;
  private CStoreSender cStoreSender;
  private AetDictionary.Aet validAet;
  private InputStream inputStream;
  private DicomInputStream dicomInputStream;
  private int port;
  private Association association;

  private DicomService dicomServiceStub;

  @Rule
  public MockitoRule rule = MockitoJUnit.rule();

  @Mock
  private ISender cMoveSenderMock;

  @Before
  public void setUp() throws Exception {
    LogUtil.Log4jToStdout("DEBUG");

    // create client
    Device device = new Device(CLIENT_AET);
    Connection conn = new Connection();
    device.addConnection(conn);
    clientAE = new ApplicationEntity(CLIENT_AET);
    device.addApplicationEntity(clientAE);
    clientAE.addConnection(conn);
    device.setExecutor(Executors.newSingleThreadExecutor());
    device.setScheduledExecutor(Executors.newSingleThreadScheduledExecutor());

    // run stub server on free port

    doReturn(0L).when(cMoveSenderMock).cmove(
        any(AetDictionary.Aet.class), anyString(), anyString(), anyString(), anyString());

    port = createDicomServer(
        new TestUtils.DicomWebClientTestBase() {
          @Override
          public JSONArray qidoRs(String path) throws DicomWebException {
            JSONArray instances = new JSONArray();
            instances.put(TestUtils.dummyQidorsInstance());
            return instances;
          }
        },
        () -> cMoveSenderMock);

    association =
        associate(SERVER_HOST_NAME, port,
            "*", UID.ExplicitVRLittleEndian);

    Attributes moveDataset = new Attributes();
    moveDataset.setString(Tag.QueryRetrieveLevel, VR.CS, "STUDY");
    moveDataset.setString(Tag.StudyInstanceUID, VR.UI, "");

    DimseRSPAssert rspAssert = new DimseRSPAssert(association, Status.Success);
    association.cmove(
        "*",
        1,
        moveDataset,
        UID.ExplicitVRLittleEndian,
        SERVER_AET,
        rspAssert);
    association.waitForOutstandingRSP();

    // Close the association.
    association.release();
    association.waitForSocketClose();

    rspAssert.assertResult();

    inputStream = Files.newInputStream(Paths.get(PATH_TO_DCM_FILE));

    CountingInputStream countingStream = new CountingInputStream(inputStream);
    dicomInputStream = new DicomInputStream(countingStream);
    validAet = new AetDictionary.Aet(SERVER_AET, SERVER_HOST_NAME, port);
    cStoreSender = new CStoreSender(clientAE);
  }

  @Test
  public void sendFile() throws IOException, InterruptedException {
    cStoreSender.cstore(validAet,
        SOP_INSTANCE_ID,
        UID.ExplicitVRLittleEndian,
        dicomInputStream);

    List<Byte> actualReceivedBytesByServer = getByteObjectArray(
        ((StubCStoreService) dicomServiceStub).getReceivedBytes());
    List<Byte> expectedBytes = getByteObjectArray(Files.readAllBytes(Paths.get(PATH_TO_DCM_FILE)));

    assertThat(expectedBytes).containsAtLeastElementsIn(actualReceivedBytesByServer);
  }

  private ArrayList<Byte> getByteObjectArray(byte[] bytes) {
    ArrayList<Byte> objectByteList = new ArrayList<>();
    for(byte b: bytes) {
      objectByteList.add(b);
    }
    return objectByteList;
  }

  @After
  public void tearDown() throws IOException, InterruptedException {
    cStoreSender.close();
  }

  private Association associate(
      String serverHostname, int serverPort, String sopClass, String syntax) throws Exception {
    AAssociateRQ rq = new AAssociateRQ();
    rq.addPresentationContext(new PresentationContext(1, sopClass, syntax));
    rq.setCalledAET(SERVER_AET);
    Connection remoteConn = new Connection();
    remoteConn.setHostname(serverHostname);
    remoteConn.setPort(serverPort);
    return clientAE.connect(remoteConn, rq);
  }

  // Creates a DICOM service and returns the port it is listening on.
  private int createDicomServer(
      IDicomWebClient dicomWebClient,
      ISenderFactory senderFactory) throws Exception {
    int serverPort = PortUtil.getFreePort();
    DicomServiceRegistry serviceRegistry = new DicomServiceRegistry();
    serviceRegistry.addDicomService(new BasicCEchoSCP());

    AetDictionary aetDict = new AetDictionary(new AetDictionary.Aet[]{
        new AetDictionary.Aet(SERVER_AET, MOVE_DESTINATION_HOSTNAME, 0)});

    dicomServiceStub = new StubCStoreService(Status.Success);
    serviceRegistry.addDicomService(dicomServiceStub);
    TransferCapability transferCapability =
        new TransferCapability(null /* commonName */, "*", TransferCapability.Role.SCP, "*");
    Device serverDevice = DeviceUtil.createServerDevice(SERVER_AET, serverPort, serviceRegistry, transferCapability);
    serverDevice.bindConnections();
    return serverPort;
  }
}
