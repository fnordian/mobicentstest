
import org.cafesip.sipunit.SipCall;
import org.mobicents.media.core.connections.RtpConnectionImpl;
import org.mobicents.media.server.impl.resource.dtmf.GeneratorImpl;
import org.mobicents.media.server.impl.resource.mediaplayer.audio.AudioPlayerImpl;

public class ConnectedCall
{
	public AudioPlayerImpl audioPlayer;
	public SipCall call;
	public RtpConnectionImpl rtpConnection;
	public GeneratorImpl dtmfGenerator;

	public ConnectedCall(SipCall call, RtpConnectionImpl rtpConnection, GeneratorImpl dtmfGenerator, AudioPlayerImpl audioPlayer)
	{
		this.call = call;
		this.rtpConnection = rtpConnection;
		this.dtmfGenerator = dtmfGenerator;
		this.audioPlayer = audioPlayer;
	}
}
