package com.mufin.ams_demo;

import java.util.HashMap;
import java.util.Map;
import com.mufin.ears.common.ComponentIds;;

public class Settings {
	// license information
	public static final String LICENSE_FILE = "ams_mobile-ipuntoweb.lic"; // license file name in assets folder
	public static final String USER_ID = "97F62"; // only characters 0-9 and a-f are allowed
	public static final Map<Integer, String> registrationInfo;
    static
	{
		registrationInfo = new HashMap<Integer, String>();
		registrationInfo.put( ComponentIds.getExtractorId(), "extractor-registration-code" );
		registrationInfo.put( ComponentIds.getAmsSessionId(), "ams_session-registration-code" );
	}

    public static boolean USE_REDIRECTOR = true;  // use redirector for cds
	public static final String  HOST = ""; // your identification server host (just hostname, without protocol)
	public static final int  PORT = 0000;  // your identification server port
	public static final String  PATH = ""; // your identification server path
	public static final long  NETWORK_TIMEOUT = 10000; // network timeout in milliseconds

	public static final String METADATA_URL = ""; // optional metadata webservice base url (url with protocol)

	// identification mode true = continuous identification, false = single mode
	public static final boolean CONTINUOUS_IDENTIFY = true;
	// audio recording duration in milliseconds
	public static final int QUERY_DURATION = 1920; // ms
	public static final int RECORDING_DURATION = 10000; // ms
	public static final int CONFIDENCE_THRESHOLD = 40;

	// enable overlap for faster search results
	public static final boolean RECORDING_OVERLAP = true;
}
