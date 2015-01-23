package com.mufin.ams_demo.components;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;

import android.util.Log;

import com.mufin.android.common.ExtractorTask;
import com.mufin.android.common.ExtractorThread;
import com.mufin.android.common.SampleRecorder;
import com.mufin.android.common.SearchTask;
import com.mufin.android.common.SearchTask.SearchWorker;
import com.mufin.android.common.SearchTask.SearchWorkerBuilder;
import com.mufin.android.common.SearchThread;
import com.mufin.ears.common.ComponentIds;
import com.mufin.ears.common.IdentifyResult;
import com.mufin.ears.common.LicenseException;
import com.mufin.ears.remote.Session;
import com.mufin.ears.xtr.Fingerprint;

public class RecordingIdentificationController
implements SampleRecorder.Listener, 
			SearchTask.Listener,
			ExtractorThread.Listener {

	/*
	 * load the native libraries on application startup
	 */
	static
	{
		System.loadLibrary( "ears-common-jni" );
		System.loadLibrary( "ears-xtr-jni" );
		System.loadLibrary( "ears-remote-http-jni" );
	}
	
	/**
	 * The enum for continuous or single recording mode
	 * @author frank
	 */
	public enum RecordingMode {
		continuous, single_scan
	}
	
	/**
	 * The enum for the supported identification types of this controller.<br/>
	 * This enum prepares the use of this class for multiple servers or hybrid cases.<br/>
	 * To extend this class for multiple parallel requests, do the following steps:
	 * <li>add new enum type, e.g. cds</li>
	 * <li>extend {@link EarsSettings} for additional data, e.g. cds session</li>
	 * <li>implement new SearchWorker, e.g. CDSSearchWorker</li>
	 * <li>extend {@link RecordingIdentificationController#onFingerprintFull(ExtractorTask, Fingerprint, long)} method,
	 * and add {@link RecordingIdentificationController#putFingerprintToTask(EarsType, Fingerprint, long, SearchWorkerBuilder)} 
	 * with new SearchWorker and appropriate enum value </li>
	 * <li>the new enum type will be reported by {@link ControllerEvents#earsResult(EarsType, ArrayList, long)} 
	 * to handle the specific result in activity</li>
	 */
	public enum EarsType {
		ams
	}
	
	/**
	 * Settings container for {@link SearchWorker}
	 * @see {@link EarsType} for detailed usage description
	 */
	public static class EarsSettings {
		/** the server host */
		private final String host;
		/** the server port */
		private final int port;
		/** the server path */
		private final String path;
		/** use redirector or connect directly to identification server */
		private final boolean useRedirector;
		/** network timeout */
		protected long networkTimeout;		
		
		public EarsSettings(String host, int port, String path, boolean useRedirector, long networkTimeout) {
			super();
			this.host = host;
			this.port = port;
			this.path = path;
			this.useRedirector = useRedirector;
			this.networkTimeout = networkTimeout;
		}
		
		public EarsSettings(EarsSettings other) {
			this(other.host, other.port, other.path, other.useRedirector, other.networkTimeout);
		}
	}
	
	private ControllerEvents handler;

	/* ########
	 * recorder
	 * ######## */
	/** the sample recorder class to handle buffers and recording time */
	private SampleRecorder recorder = null;
	/** flag indicating that audio record is running */
	private boolean recording = false;
	/** the current recording mode */
	private RecordingMode recordingMode = RecordingMode.continuous;
	/** the duration of a single query in ms */
	private int queryDuration = -1;
	/** the delay between recording and send to identify in ms */
	private int delayDuration = -1;
	/** the maximum trial duration in ms (ignored if continuous mode) */
	private int recordingDuration = -1;
	/** use recording overlap */
	private boolean overlap = false;
	/** the recording overlap, interval [0..1) */
	private static final float DEFAULT_OVERLAP = 0.4f;
	
	private volatile EarsSettings settings;
	
	/* ########
	 * EARS
	 * ######## */
	/** the identify thread/queue list, supports multiple threads for identification like hybrid cases */
	private volatile HashMap<EarsType, SearchTask> tasks;
	
	/* ########
	 * XTR
	 * ######## */
	/** the extractor thread/queue */
	private ExtractorTask xtrTask = null;
	
	/**
	 * ctor
	 * @param recordingMode the recording config
	 * @param queryDuration the duration of a single query in ms
	 * @param delayDuration the delay between recording and send to identify in ms
	 * @param recordingDuration the maximum trial duration in ms (ignored if continuous mode)
	 * @param overlap enable/disable overlap
	 * @param settings the settings object for ears search instance
	 */
	public RecordingIdentificationController( RecordingMode recordingMode,
							int queryDuration, int delayDuration, int recordingDuration,
							boolean overlap, EarsSettings settings ) {
		super();
		this.recordingMode = recordingMode;
		this.queryDuration = queryDuration;
		this.delayDuration = delayDuration;
		this.recordingDuration = recordingDuration;
		this.overlap = overlap;

		this.settings = new EarsSettings( settings );
	}
	
	/**
	 * start the audio recorder and notify EarsTask on recording
	 */
	public void startRecorder()
	{
		if(isRecording()) {
			Log.d( getClass().getName(), "start recording: no action taken. recording: " + isRecording() );
			return;
		}
		
		// extraction thread need to know what kind of samples will come
		try
        {
	        xtrTask = new ExtractorThread( queryDuration, overlap ? DEFAULT_OVERLAP : 0.f,
	        							SampleRecorder.SAMPLE_RATE, 
	        							SampleRecorder.CHANNEL_CONFIGURATION_COUNT );
        }
        catch( LicenseException e )
        {
        	if(handler != null) handler.earsError( EarsType.ams, e.getMessage(), -1 );
        	return;
        }
		// run
		xtrTask.start( this );
		
		// init audio recording
        // duration is an experimental value, that is compromise between fast extractor update and
        // and heavy delegate calls, try best results...
		
		// for best performance in ExtractorThread, 
		// duration should be a multiple of extractor granularity in milliseconds
		// e.g. 
		// granularity is 480 samples @ 16000Hz sampling rate
		// 480 * 1000 / 16000 = 30 ms
		// duration should be n * 30ms
        int durationMs = 600; // duration in ms
        
		int recordingDuration_ = (
				recordingMode == RecordingMode.continuous ?
				SampleRecorder.RECORDING_DURATION_UNLIMITED :
				recordingDuration
		);
		
		recording = true;
		
		recorder = new SampleRecorder( 
					durationMs,
					recordingDuration_,
					delayDuration,
					this );
		
		recorder.start();
	}
	
	/**
	 * stops the audio recorder and stop identification threads
	 */
	public void stopRecorder()
	{
		if(!isRecording()) {
			Log.d( getClass().getName(), "stop recording: no action taken. recording: " + isRecording() );
			return;
		}
		
		recording = false;

		// release/reset extractor resources
		if( xtrTask != null )
		{
			xtrTask.cancel();
			xtrTask = null;
		}

		// release/reset rears service resources
		if( tasks != null )
		{
			for (Entry<EarsType, SearchTask> entry : tasks.entrySet())
			{
				entry.getValue().cancel();
			}
		}
		
		// if it is already ended, no effect
		if(recorder != null) 
			recorder.cancel();
	}
	
	/**
	 * close background tasks and remove handlers and references.<br/>
	 * calling any method of the controller will result in undefined behavior, after destroy.
	 */
	public void destroy()
	{
		stopRecorder();
		
		// tasks and xtrTask already cleaned in stopRecorder()
		
		// invalidate identification thread list
		if(tasks != null)
		{
			tasks.clear();
			tasks = null;
		}
		
		handler = null;
		
		recorder = null;
	}
	
	/**
	 * @return is recorder recording
	 */
	public boolean isRecording() {
		return recording;
	}

	public RecordingMode getRecordingMode() {
		return recordingMode;
	}

	public void setRecordingMode(RecordingMode recordingMode) {
		this.recordingMode = recordingMode;
	}

	public int getQueryDuration() {
		return queryDuration;
	}

	public void setQueryDuration(int queryDuration) {
		this.queryDuration = queryDuration;
	}

	public int getDelayDuration() {
		return delayDuration;
	}

	public void setDelayDuration(int delayDuration) {
		this.delayDuration = delayDuration;
	}

	public int getRecordingDuration() {
		return recordingDuration;
	}

	public void setRecordingDuration(int recordingDuration) {
		this.recordingDuration = recordingDuration;
	}
	
	public boolean getRecordingIsOverlapping() {
		return overlap;
	}

	public void setRecordingIsOverlapping(boolean overlap) {
		this.overlap = overlap;
	}

	/**
	 * sets the connetion host and port for the audioid server.<br/>
	 * changes are applied after restarting the recorder
	 * @param settings the settings object for ears search instance
	 */
	public void setSettings( EarsSettings settings ) {
		this.settings = new EarsSettings( settings );
	}

	/**
	 * sets the handler to get intermediate events from sample recorder and LarsTask
	 * @param handler
	 */
	public void setControllerEventsHandler(ControllerEvents handler) {
		this.handler = handler;
	}

	/**
	 * get the EARS version from library interface
	 * @return the EARS version string
	 */
	public String getEarsVersion() {
		return "Session " + Session.version();
	}
	
	/**
	 * walk the tasks list and return the type of the given task source
	 * @param source
	 * @return the type of the given task source
	 */
	private EarsType getSourceType( SearchTask source ) {
		for (Entry<EarsType, SearchTask> entry : tasks.entrySet())
		{
			if(entry.getValue() == source)
			{
				return entry.getKey();
			}
		}
		
		throw new IllegalArgumentException( "No key for source (" + source + ") found in tasks map." );
	}
	
	/**
	 * add {@link Fingerprint} to the identification queue for the specified {@link EarsType}.<br/>
	 * if the task for this {@link EarsType} isn't running, the task will be created and registered in {@link #tasks} list.
	 * @param earsType the type to search
	 * @param fp the fingerprint to search
	 * @param fingerprintPosition the query position in the audio
	 * @param builder the builder of the searchworker for lazy initialization of {@link SearchWorker}, if the task is newly created
	 * @throws LicenseException 
	 */
	private void putFingerprintToTask( EarsType earsType, Fingerprint fp, long fingerprintPosition, SearchWorkerBuilder builder ) throws LicenseException
	{
		if( tasks == null )
		{
			tasks = new HashMap<EarsType, SearchTask>();
		}

		SearchTask task = tasks.get( earsType );
		
		if( task == null || !task.isRunning() )
		{
			SearchWorker worker = builder.build();
			
			// re-create the thread and start again, if first call other thread ended preceding run
			// start identify and extractor thread/queue
			task = new SearchThread();
			//  the resource-ownership move to SearchTask
			task.start( worker, this );
			tasks.put( earsType, task );
		}

		try {
			// send the query
			task.putFingerprint( fp, fingerprintPosition );
		} catch(IllegalStateException e) {
			// TODO: handle multiple long running and give message to controller owner
			Log.w( getClass().getName(), "skip current identification: previous identification running" );
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final void onRecorderInit( SampleRecorder source, int sampleRate, int channels ) {
		Log.d( getClass().getName(), "recorderInited" );
		
		if(handler != null) handler.recorderInited( sampleRate, channels );
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final void onRecorderUpdate( SampleRecorder source, short[] samples, int samplesRecordedCount ) {
		// if recording was stopped
		if(!isRecording()) return;
		
		if(handler != null) handler.recorderUpdate( samples, samplesRecordedCount );
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final void onRecorded( SampleRecorder source, short[] samples ) {
		// if recording was stopped
		if(!isRecording()) return;
		
		xtrTask.putSamples( samples );
		
		if(handler != null) handler.recorderRecorded( samples, false );
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final void onRecorderFinished( SampleRecorder source, short[] samples ) {
		// if recording was stopped
		if(!isRecording()) return;
		
		xtrTask.putSamples( samples );
		
		if(handler != null) handler.recorderRecorded( samples, true );
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final void onRecorderInitError( SampleRecorder source ) {
		if(handler != null) handler.recorderInitError();
	}
	
	/**
	 * {@inheritDoc}
	 */
	@Override
	public void onFingerprintFull( ExtractorTask source, Fingerprint fp, long fingerprintPosition ) {
		// Lazy build worker, to prevent session create if not necessary.
		// Builder.build() is called in putFingerprintToTask(), if there is no thread for given EarsType available,
		// the builder creates a new worker and therefore a new server session.
		// The resource management of the session object, will be owned (and released) by the created SearchTask.
		SearchWorkerBuilder amsBuilder = new SearchWorkerBuilder() {
			@Override
			public SearchWorker build() throws LicenseException {
				return new RearsSearchWorker( 
						settings.host, 
						settings.port, 
						settings.path,
						settings.useRedirector,
						settings.networkTimeout );
			}
		};
		
		try
        {
	        putFingerprintToTask( EarsType.ams, fp, fingerprintPosition, amsBuilder );
        }
        catch( LicenseException e )
        {
        	stopRecorder();
        	
    		if(handler != null) 
			{
    			handler.earsError( EarsType.ams, e.getMessage(), -1 );
			}
        }
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void onExtractorError( ExtractorTask source, String errorMessage, Integer errorCode ) {
		if(handler != null) handler.extractorError( errorMessage, errorCode );
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void onSearchError( SearchTask source, String errorMessage, Integer errorCode ) {
		EarsType key = getSourceType( source );
		if(handler != null) handler.earsError( key, errorMessage, errorCode );
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void onSearchResult( SearchTask source, 
							ArrayList<IdentifyResult> identifyResult,
							long searchDuration ) {
		EarsType key = getSourceType( source );
		if(handler != null) handler.earsResult( key, identifyResult, searchDuration );
	}

	/**
	 * send given samples to LarsTask and identify in one step.<br/>
	 * is used for debugging features, respectively for load pcm audio file.<br/>
	 * this method should be called async.
	 * <ul>
	 * <li>startRecord();</li>
	 * <li>putSamples();</li>
	 * <li>endRecord();</li>
	 * </ul>
	 * @param samples
	 * @throws LicenseException 
	 */
	public void identifySamplesDirectly(short[] samples) throws LicenseException
	{
		// do not try, if already running
		if(isRecording()) return;
		
		recording = true;
		
		int duration = samples.length * 1000 / SampleRecorder.SAMPLE_RATE;
		
		Log.d( getClass().getName(), "identifySamplesDirectly samples.length: " + samples.length + " duration: " + duration );
		
		int sampleQueryLength = (int)(queryDuration / 1000f * SampleRecorder.SAMPLE_RATE);
		int queryCount = samples.length / sampleQueryLength;
		
		Log.d( getClass().getName(), "identifySamplesDirectly sampleQueryLength: " + sampleQueryLength + " queryCount: " + queryCount );
		
		if(xtrTask == null || !xtrTask.isRunning())
		{
			// extraction thread need to know what kind of samples will come
			xtrTask = new ExtractorThread( queryDuration, overlap ? DEFAULT_OVERLAP : 0.f,
					   SampleRecorder.SAMPLE_RATE, 1 );
			// run
			xtrTask.start(this);
		}
		
		for (int i = 0; i < queryCount; i++) {
			Log.d( getClass().getName(), "identifySamplesDirectly i: " + i + " from: " + (i*sampleQueryLength) + " to: " + (i*sampleQueryLength+sampleQueryLength) );
			
			short[] query = new short[sampleQueryLength];
			System.arraycopy(samples, i*sampleQueryLength, query, 0, sampleQueryLength);
			
			xtrTask.putSamples(query);
		}
		
		// just a simple wait for fingerprint loader ;)
		// fingerprint loader should fire event if finished to handle this correct
		try {
			Thread.sleep(5000);
		} catch (InterruptedException e) {}
		
		recording = false;
		Log.d( getClass().getName(), "identifySamplesDirectly set recording to false");
	}

	private class RearsSearchWorker implements SearchTask.SearchWorker
	{
		private Session session;
		
		public RearsSearchWorker(String host, int port, String path, boolean useRedirector, long timeout) throws LicenseException {
			if(useRedirector) {
				session = new Session(ComponentIds.getAmsSessionId(), host, port, path, timeout);
			} else {
				session = new Session(ComponentIds.getAmsSessionId(), host, port, timeout);
			}
		}
		
		@Override
		public int search(Fingerprint query, long queryPosition, int numResults,
				List<IdentifyResult> result) throws LicenseException {
			// set timestamp to let the client do the time measurement
			query.setTimestamp(queryPosition);
			int searchRes = session.search(query, numResults, result, false);
			return searchRes;
		}
		
		public void destroy() {
			Session.destroy(session);
			session = null;
		}
	}

	/**
	 * Events triggered by controller. Implement the things you need.
	 * 
	 * Why this is not an interface? See:
	 * "Abstract Classes versus Interfaces"
	 * http://docs.oracle.com/javase/tutorial/java/IandI/abstract.html
	 */
	public static abstract class ControllerEvents
	{
		/**
		 * recorder inited
		 * @param sampleRate the audio recorder sampling rate
		 * @param the audio recorder channel config
		 * @see SampleRecorder.Listener#onRecorderInit(int, int)
		 */
		protected void recorderInited( int sampleRate, int channels ){}
		/**
		 * update recording status
		 * @param samples the sample buffer
		 * @param samplesRecordedCount the fill counter of the buffer
		 * @see SampleRecorder.Listener#onRecorderUpdate(short[], int)
		 */
		protected void recorderUpdate( short[] samples, int samplesRecordedCount ){}
		/**
		 * sample buffer filled and ready to identify
		 * @param samples the sample buffer
		 * @param recorderFinished recorder is still in query loop or reached {@link #getRecordingDuration()} duration
		 * @see {@link SampleRecorder.Listener#onRecorded(short[])} {@link SampleRecorder.Listener#onRecorderFinished(short[])}
		 */
		protected void recorderRecorded( short[] samples, boolean recorderFinished ){}
		/**
		 * recorder init error
		 * @see SampleRecorder.Listener#onRecorderInitError()
		 */
		protected void recorderInitError(){}
		
		/**
		 * got error from ExtractorTask
		 * @param errorMessage the message string
		 * @param errorCode optional error code
		 * @see ExtractorTask.Listener#onExtractorError(ExtractorTask, String, Integer)
		 */
		protected void extractorError( String errorMessage, Integer errorCode ){}
		
		/**
		 * got error from SearchTask
		 * @param errorMessage the message string
		 * @param errorCode optional error code
		 * @see SearchTask.Listener#onEarsError(SearchTask, String, Integer)
		 */
		protected void earsError( EarsType earsType, String errorMessage, Integer errorCode ){}
		/**
		 * result returned from SearchTask
		 * @param identifyResult the result list, or null if no result
		 * @param searchDuration the duration of the server request
		 * @see SearchTask.Listener#onEarsResult(SearchTask, ArrayList, long)
		 */
		protected void earsResult( EarsType earsType, ArrayList<IdentifyResult> identifyResult, long searchDuration ){}
	}
}
