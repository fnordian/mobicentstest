import gov.nist.javax.sip.header.ContentType;
import gov.nist.javax.sip.header.ProxyAuthenticate;
import gov.nist.javax.sip.header.ProxyAuthorization;
import org.cafesip.sipunit.*;
import org.junit.Test;
import org.mobicents.media.core.connections.RtpConnectionImpl;
import org.mobicents.media.server.component.DspFactoryImpl;
import org.mobicents.media.server.component.audio.AudioComponent;
import org.mobicents.media.server.component.audio.AudioInput;
import org.mobicents.media.server.component.audio.AudioMixer;
import org.mobicents.media.server.component.oob.OOBComponent;
import org.mobicents.media.server.component.oob.OOBMixer;
import org.mobicents.media.server.impl.dsp.audio.g711.alaw.Encoder;
import org.mobicents.media.server.impl.resource.dtmf.GeneratorImpl;
import org.mobicents.media.server.impl.resource.mediaplayer.audio.AudioPlayerImpl;
import org.mobicents.media.server.impl.rtp.ChannelsManager;
import org.mobicents.media.server.impl.rtp.RtpClock;
import org.mobicents.media.server.io.network.UdpManager;
import org.mobicents.media.server.scheduler.Scheduler;
import org.mobicents.media.server.spi.ConnectionMode;
import org.mobicents.media.server.spi.ModeNotSupportedException;
import org.mobicents.media.server.spi.ResourceUnavailableException;

import javax.sip.InvalidArgumentException;
import javax.sip.header.Header;
import javax.sip.message.Message;
import java.io.IOException;
import java.net.*;
import java.text.ParseException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import static org.cafesip.sipunit.SipAssert.assertLastOperationSuccess;
import static org.junit.Assert.fail;

public class RtpTest
{

	private SipStack sipStack;
	private Scheduler scheduler;
	private UdpManager udpManager;
	private DspFactoryImpl dspFactory;
	private RtpClock oobClock;
	private RtpClock rtpClock;
	private ChannelsManager channelsManager;
	private String username = "1016848e0";
	private String password = "jzV8bLTDHc9v";


	private String getLocalAddress()
	{

		try
		{
			Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();

			while (nics.hasMoreElements())
			{
				NetworkInterface nic = nics.nextElement();

				if (!nic.isUp() || nic.isLoopback() || nic.getName().startsWith("vbox"))
				{
					continue;
				}

				Enumeration<InetAddress> nicAddrs = nic.getInetAddresses();
				while (nicAddrs.hasMoreElements())
				{
					InetAddress ia = nicAddrs.nextElement();
					if (ia.getClass().equals(Inet4Address.class))
					{
						return ia.getHostAddress();
					}
				}
			}
		}
		catch (SocketException e)
		{
			e.printStackTrace();
		}
		return null;

	}

	public SipStack initSip() throws Exception
	{
		Properties props = new Properties();

		props.setProperty("javax.sip.IP_ADDRESS", getLocalAddress());

		sipStack = new org.cafesip.sipunit.SipStack("udp", 5555, props);
		scheduler = new Scheduler();
		udpManager = new UdpManager(scheduler);
		udpManager.setBindAddress(getLocalAddress());
		udpManager.setExternalAddress(getLocalAddress());
		channelsManager = new ChannelsManager(udpManager);
		channelsManager.setScheduler(scheduler);
		scheduler.setClock(channelsManager.getClock());
		scheduler.start();
		dspFactory = new DspFactoryImpl();

		rtpClock = new RtpClock(channelsManager.getClock());
		oobClock = new RtpClock(channelsManager.getClock());
		//	statistics = new RtpStatistics(rtpClock);

		return sipStack;
	}


	public static SipPhone createPhone(SipStack sipStack, String proxy, Credential credential) throws ParseException, InvalidArgumentException
	{
		SipPhone ua = sipStack.createSipPhone(proxy, "udp", 5060, "sip:" + credential.getUser() + "@sipgate.de");

		ua.addUpdateCredential(credential);
		ua.register(credential.getUser(), credential.getPassword(), null, 1800, 1000);
		assertLastOperationSuccess(ua);

		return ua;
	}

	public static <T> ArrayList<T> arrayList(T... elements)
	{
		return new ArrayList<>(Arrays.asList(elements));
	}

	public static <T> ArrayList<T> arrayList(ArrayList<T> list, T... elements)
	{
		ArrayList<T> ret = new ArrayList<T>(Arrays.asList(elements));

		if (list != null)
		{
			ret.addAll(list);
		}

		return ret;
	}

	public ProxyAuthorization generateAuthentication(SipStack sipStack, Message response, String username, String password, String method, String uri) throws ParseException
	{
		ProxyAuthenticate header = (ProxyAuthenticate) response.getHeader("Proxy-Authenticate");

		String authResponse = MessageDigestAlgorithm.calculateResponse(header.getAlgorithm(), username, header.getRealm(), password, header.getNonce(), null, "", method, uri, null, null);

		ProxyAuthorization authHeader = new ProxyAuthorization();
		authHeader.setScheme(header.getScheme());
		authHeader.setNonce(header.getNonce());
		authHeader.setRealm(header.getRealm());
		authHeader.setResponse(authResponse);
		authHeader.setURI(sipStack.getAddressFactory().createURI(uri));
		authHeader.setAlgorithm("MD5");
		authHeader.setUsername(username);

		return authHeader;
	}

	public Optional<SipCall> makeCall(SipStack sipStack, SipPhone ua, String requestUri, String fromUri, Credential credential, ArrayList<Header> additionalHeaders, String ourSdp) throws IOException
	{
		ArrayList<Header> headers = ourSdp != null ? arrayList(additionalHeaders, new ContentType("application", "sdp")) : additionalHeaders;

		SipCall a = ua.createSipCall();
		a.listenForMessage();

		a.initiateOutgoingCall(
				fromUri, requestUri, null,
				headers,
				null, ourSdp);

		a.waitOutgoingCallResponse();
		SipResponse mostRecentResponse = a.findMostRecentResponse(407);

		if (mostRecentResponse != null)
		{
			try
			{
				ProxyAuthorization authorization = generateAuthentication(sipStack, mostRecentResponse.getMessage(), credential.getUser(), credential.getPassword(), "INVITE", requestUri);

				a.initiateOutgoingCall(
						fromUri, requestUri, null,
						arrayList(headers, authorization),
						null, ourSdp);

				a.waitOutgoingCallResponse();
				mostRecentResponse = a.findMostRecentResponse(100);

				if (mostRecentResponse == null)
				{
					return Optional.empty();
				}
			}
			catch (ParseException e)
			{
				return Optional.empty();
			}
		}

		return Optional.of(a);
	}


	public static void sendDtmf(ConnectedCall connectedCall, String dtmfSequence) throws Exception
	{

		LinkedList<String> dtmfSeq = new LinkedList<>(Arrays.asList(dtmfSequence.split("")));

		String firstDtmf = dtmfSeq.remove();

		connectedCall.dtmfGenerator.setOOBDigit(firstDtmf);
		System.out.println("dtmf gen event: " + firstDtmf);

		connectedCall.dtmfGenerator.setToneDuration(200);

		connectedCall.dtmfGenerator.addListener(event -> {

			if (!dtmfSeq.isEmpty())
			{
				boolean started = event.getSource().isStarted();

				System.out.println("current digit: " + event.getSource().getMediaTime());

				if (started) {

					event.getSource().stop();
					event.getSource().setDigit("");

				} else
				{
					String nextDtmf = dtmfSeq.remove();
					System.out.println("dtmf gen event: " + started + " next: " + nextDtmf);


					event.getSource().setDigit("");
					event.getSource().setOOBDigit(nextDtmf);


					event.getSource().setMediaTime(0);
					event.getSource().activate();
					event.getSource().start();

				}
			}

		});

		connectedCall.dtmfGenerator.activate();
		connectedCall.dtmfGenerator.start();
	}

	private AudioPlayerImpl connectionAudioGenerator(RtpConnectionImpl rtpConnection) throws MalformedURLException, ResourceUnavailableException
	{
		AudioPlayerImpl audioPlayer = new AudioPlayerImpl("audio-generator", scheduler);
		audioPlayer.setVolume(-10);

		audioPlayer.setURL("https://raw.githubusercontent.com/fnordian/mobicentstest/master/src/test/resources/ansage.wav");

		AudioComponent audioComponent = new AudioComponent(1);
		audioComponent.addInput(audioPlayer.getAudioInput());
		audioComponent.updateMode(true, false);
		AudioMixer audioMixer = new AudioMixer(scheduler);
		audioMixer.addComponent(rtpConnection.getAudioComponent());

		audioMixer.addComponent(audioComponent);

		audioMixer.start();

		audioPlayer.activate();
		audioPlayer.start();

		return audioPlayer;
	}


	private GeneratorImpl connectionDtmfGenerator(RtpConnectionImpl rtpConnection) throws MalformedURLException, ResourceUnavailableException
	{
		GeneratorImpl dtmfGenerator = new GeneratorImpl("dtmf-generator",scheduler);
		dtmfGenerator.setVolume(-255);

		OOBComponent dtmfOob = new OOBComponent(1);
		dtmfOob.updateMode(true, false);
		dtmfOob.addInput(dtmfGenerator.getOOBInput());

		OOBMixer oobMixer = new OOBMixer(scheduler);
		oobMixer.addComponent(rtpConnection.getOOBComponent());
		oobMixer.addComponent(dtmfOob);
		oobMixer.start();

		return dtmfGenerator;
	}

	public Future<Optional<ConnectedCall>> makeConnectedCall(SipStack sipStack, SipPhone ua, String requestUri, String fromUri, Credential credential) throws Exception
	{

		RtpConnectionImpl rtpConnection = new RtpConnectionImpl(1, channelsManager, dspFactory);
		rtpConnection.generateOffer();
		String ourSdp = rtpConnection.getDescriptor();

		Optional<SipCall> sipCall = makeCall(sipStack, ua, requestUri, fromUri, credential, null, ourSdp);

		return CompletableFuture.supplyAsync(() -> {

					boolean answered = sipCall.get().waitForAnswer(10000);

					Optional<SipResponse> response = sipCall.map((call) -> call.findMostRecentResponse(200));

					if (response.isPresent())
					{
						sipCall.get().sendInviteOkAck();



						try
						{
							rtpConnection.setOtherParty(response.get().getRawContent());
							rtpConnection.setMode(ConnectionMode.SEND_RECV);

							AudioPlayerImpl audioPlayer = connectionAudioGenerator(rtpConnection);
							GeneratorImpl dtmfGenerator = connectionDtmfGenerator(rtpConnection);


							return Optional.of(new ConnectedCall(sipCall.get(), rtpConnection, dtmfGenerator, audioPlayer));
						}
						catch (IOException e)
						{
							e.printStackTrace();
						}
						catch (ModeNotSupportedException e)
						{
							e.printStackTrace();
						}
						catch (ResourceUnavailableException e)
						{
							e.printStackTrace();
						}

					}

					return Optional.empty();

				}
		);

	}


	@Test
	public void callProducesVoiceAndDtmfData() throws Exception
	{
		SipStack sipStack = initSip();
		Credential credential = new Credential("sipgate.de", username, password);
		SipPhone sipPhone = createPhone(sipStack, "proxy.dev.sipgate.de", credential);
		String sdp = null;
		//makeCall(sipStack, sipPhone, "sip:10000@sipgate.de", "sip:foo@bar.com", credential, new ArrayList<Header>(), sdp);

		Future<Optional<ConnectedCall>> connectedCallFuture = makeConnectedCall(sipStack, sipPhone, "sip:10000@sipgate.de", "sip:foo@bar.com", credential);

		if (connectedCallFuture.get().isPresent()) {
			sendDtmf(connectedCallFuture.get().get(), "1");
			Thread.sleep(2000);
			connectedCallFuture.get().get().call.dispose();
		} else {
			fail("unable to make call");
		}



	}

}
