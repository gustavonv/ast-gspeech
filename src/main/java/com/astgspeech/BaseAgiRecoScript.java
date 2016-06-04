package com.astgspeech;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.asteriskjava.fastagi.AgiChannel;
import org.asteriskjava.fastagi.AgiException;
import org.asteriskjava.fastagi.AgiRequest;
import org.asteriskjava.util.Log;
import org.asteriskjava.util.LogFactory;

import com.astgspeech.core.BaseEAgiScript;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.speech.v1.AudioRequest;
import com.google.cloud.speech.v1.InitialRecognizeRequest;
import com.google.cloud.speech.v1.InitialRecognizeRequest.AudioEncoding;
import com.google.cloud.speech.v1.RecognizeRequest;
import com.google.cloud.speech.v1.RecognizeResponse;
import com.google.cloud.speech.v1.RecognizeResponse.EndpointerEvent;
import com.google.cloud.speech.v1.SpeechGrpc;
import com.google.cloud.speech.v1.SpeechRecognitionAlternative;
import com.google.cloud.speech.v1.SpeechRecognitionResult;
import com.google.protobuf.ByteString;
import com.google.protobuf.TextFormat;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.auth.ClientAuthInterceptor;
import io.grpc.netty.NegotiationType;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;

public abstract class BaseAgiRecoScript extends BaseEAgiScript   {

	private final Log logger = LogFactory.getLog(getClass());
	
	private final ManagedChannel channel;

	private final SpeechGrpc.SpeechStub stub;

	private int samplingRate;

	private static final List<String> OAUTH2_SCOPES = Arrays.asList("https://www.googleapis.com/auth/cloud-platform");
	
	public abstract boolean onNext( String transcript, float confidence, SpeechRecognitionResult speechRecognitionResult, RecognizeResponse response );
	public abstract boolean onFinal( String transcript, float confidence, SpeechRecognitionResult speechRecognitionResult, RecognizeResponse response );
	
	public abstract void onError(Throwable error);
		
	public abstract boolean onEvent(EndpointerEvent endpoint);	
	
	public abstract void onCompleted(); 
	
	public BaseAgiRecoScript() {
		super();
		String host = "speech.googleapis.com";
		Integer port = 443;
		this.samplingRate = 8000;
		GoogleCredentials creds;
		try {
			creds = GoogleCredentials.getApplicationDefault();
		} catch (IOException e) {
			throw new RuntimeException ( e );
		}
		creds = creds.createScoped(OAUTH2_SCOPES);
		channel = NettyChannelBuilder.forAddress(host, port).negotiationType(NegotiationType.TLS)
				.intercept(new ClientAuthInterceptor(creds, Executors.newSingleThreadExecutor())).build();
		stub = SpeechGrpc.newStub(channel);
		System.err.println("Created stub for " + host + ":" + port);
	}
	

	@SuppressWarnings("restriction")
	private static FileInputStream getFIS() {
		FileDescriptor fd = new FileDescriptor();
		sun.misc.SharedSecrets.getJavaIOFileDescriptorAccess().set(fd, 3);
		FileInputStream fin = new FileInputStream(fd);
		return fin;
	}

	public void shutdown() throws InterruptedException {
		channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
	}

	/** Send streaming recognize requests to server. */
	public void recognize() throws InterruptedException, IOException {
		final CountDownLatch finishLatch = new CountDownLatch(1);
		final InfiniteLoop infinite = new InfiniteLoop( true );
		final BaseAgiRecoScript script = this;
		StreamObserver<RecognizeResponse> responseObserver = new StreamObserver<RecognizeResponse>() {
			@Override
			public void onNext(RecognizeResponse response) {
				System.err.println("Received response: " + TextFormat.printToString(response));
				EndpointerEvent endpoint = response.getEndpoint();
				if( endpoint != null ) {
					boolean continueRet = script.onEvent( endpoint );
					if( !continueRet ) {
						infinite.setInfinite( false );
						return;
					}
				}
				for (SpeechRecognitionResult speechRecognitionResult : response.getResultsList()) {
					boolean isFinal = speechRecognitionResult.getIsFinal(); 
					for (SpeechRecognitionAlternative speechRecognitionAlternative : speechRecognitionResult.getAlternativesList()) {
						String transcript = speechRecognitionAlternative.getTranscript();
						boolean continueRet = true;
						if( isFinal ) {
							continueRet = script.onFinal( transcript, speechRecognitionAlternative.getConfidence(), speechRecognitionResult, response );
						} else {
							continueRet = script.onNext( transcript, speechRecognitionAlternative.getConfidence(), speechRecognitionResult, response );
						}
						if( !continueRet ) {
							infinite.setInfinite( false );
							break;
						}
					}
				}
			}

			@Override
			public void onError(Throwable error) {
				Status status = Status.fromThrowable(error);
				System.err.println("recognize failed: {0}" + status);
				finishLatch.countDown();
				script.onError(error);
			}

			@Override
			public void onCompleted() {
				System.err.println("recognize completed.");
				finishLatch.countDown();
				script.onCompleted();
			}
		};
		//execute( new SayDigitsCommand("1") );
		//execute( new StreamFileCommand("beep") );
		
		StreamObserver<RecognizeRequest> requestObserver = stub.recognize(responseObserver);
		try {
			// Build and send a RecognizeRequest containing the parameters for
			// processing the audio.
			InitialRecognizeRequest initial = InitialRecognizeRequest.newBuilder()
					.setEncoding(AudioEncoding.LINEAR16)
					.setSampleRate(samplingRate)
					.setLanguageCode("es-CO")
					.setContinuous(true)
					.setEnableEndpointerEvents(true)
					.setInterimResults(true)
					.build();
			RecognizeRequest firstRequest = RecognizeRequest.newBuilder().setInitialRequest(initial).build();
			requestObserver.onNext(firstRequest);

			// Open audio file. Read and send sequential buffers of audio as
			// additional RecognizeRequests.
			FileInputStream inFIS = getFIS();//new FileInputStream(new File(file));
			DataInputStream in = new DataInputStream( inFIS );
			// For LINEAR16 at 16000 Hz sample rate, 3200 bytes corresponds to
			// 100 milliseconds of audio.
			byte[] buffer = new byte[3200];
			int bytesRead;
			int totalBytes = 0;
			//while ((bytesRead = in.read(buffer)) != -1) {
			Date now = new Date();
			Date before = now;
			while(infinite.isInfinite()){
				try{					
					int available = in.available();
					//System.err.println("Data available:" + available );					
					if( available >= 3200 ) {
						in.readFully(buffer);
						bytesRead = buffer.length;
						int delay = 3;
						now = new Date();
						long timeNow = now.getTime();
						long elapsed = timeNow - before.getTime();					
						System.err.println("Sent " + bytesRead + " elapsed:" + elapsed + " msec:" + timeNow );
						before = now;
						if( elapsed < 180 ) {
							//continue;
							delay = 20;
						}
						
						totalBytes += bytesRead;
						AudioRequest audio = AudioRequest.newBuilder().setContent(ByteString.copyFrom(buffer, 0, bytesRead))
								.build();
						RecognizeRequest request = RecognizeRequest.newBuilder().setAudioRequest(audio).build();
						requestObserver.onNext(request);
						// To simulate real-time audio, sleep after sending each audio
						// buffer.
						// For 16000 Hz sample rate, sleep 100 milliseconds.
						Thread.sleep( ( samplingRate / 40) -  delay );
					} else {
						if( getChannelStatus() == 0 ) {
							break;
						}
						Thread.sleep( ( samplingRate / 40) -  3 );
						now = new Date();
						long timeNow = now.getTime();
						long elapsed = timeNow - before.getTime();					
						System.err.println("No DATA elapsed:" + elapsed + " msec:" + timeNow );
						if( elapsed > 3000 ) {
							break;
						}
					}
					
				} catch ( EOFException eof ) {
					System.err.println("EOF " + totalBytes );
					break;
				} catch ( AgiException aexc ){
					System.err.println("AgiException " + totalBytes );
					break;
				}
			}
			System.err.println("Sent " + totalBytes + " bytes from audio file: ");
		} catch (RuntimeException e) {
			// Cancel RPC.
			requestObserver.onError(e);
			throw e;
		}
		// Mark the end of requests.
		requestObserver.onCompleted();

		// Receiving happens asynchronously.
		finishLatch.await(1, TimeUnit.MINUTES);
	}	

	@Override
	public void service(AgiRequest request, AgiChannel channel) throws AgiException {
		logger.debug("service '" + request + "' " + channel);
		try {
			this.recognize();
		} catch (InterruptedException | IOException e) {
			e.printStackTrace( System.err );
		}
	}
}
