package net.osmand.plus.voice;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.os.Build;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeech.OnInitListener;
import android.speech.tts.TextToSpeech.OnUtteranceCompletedListener;
import android.widget.Toast;

import net.osmand.PlatformUtil;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.routing.VoiceRouter;
import net.osmand.plus.settings.backend.ApplicationMode;
import net.osmand.plus.settings.backend.OsmandPreference;
import net.osmand.util.Algorithms;

import org.apache.commons.logging.Log;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;


public class TTSCommandPlayerImpl extends AbstractPrologCommandPlayer {
	public final static String PEBBLE_ALERT = "PEBBLE_ALERT";
	public final static String WEAR_ALERT = "WEAR_ALERT";

	private static final String CONFIG_FILE = "_ttsconfig.p";
	private static final int[] TTS_VOICE_VERSION = new int[] { 102, 103 }; // !! MUST BE SORTED  
	// No more TTS v101 support because of too many changes
	// TODO: We could actually remove v102 support, I am done updating all existing 35 TTS voices to v103. Hardy, July 2016
	private static final Log log = PlatformUtil.getLog(TTSCommandPlayerImpl.class);
	private static TextToSpeech mTts;
	private static String ttsVoiceStatus = "-";
	private static String ttsVoiceUsed = "-";
	private Context mTtsContext;
	private HashMap<String, String> params = new HashMap<String, String>();
	private VoiceRouter vrt;

	public TTSCommandPlayerImpl(Activity ctx, ApplicationMode applicationMode, VoiceRouter vrt, String voiceProvider)
			throws CommandPlayerException {
		super((OsmandApplication) ctx.getApplicationContext(), applicationMode, voiceProvider, CONFIG_FILE, TTS_VOICE_VERSION);
		this.vrt = vrt;
		if (Algorithms.isEmpty(language)) {
			throw new CommandPlayerException(
					ctx.getString(R.string.voice_data_corrupted));
		}
		OsmandApplication app = (OsmandApplication) ctx.getApplicationContext();
		if(app.accessibilityEnabled()) {
			cSpeechRate = app.getSettings().SPEECH_RATE.get();
		}
		initializeEngine(app, ctx);
		params.put(TextToSpeech.Engine.KEY_PARAM_STREAM, app.getSettings().AUDIO_MANAGER_STREAM
				.getModeValue(getApplicationMode()).toString());
	}
	
	

	/**
	 * Since TTS requests are asynchronous, playCommands() can be called before
	 * the TTS engine is done. We use this field to keep track of concurrent tts
	 * activity. Where tts activity is defined as the time between tts.speak()
	 * and the call back to onUtteranceCompletedListener().  This allows us to
	 * optimize use of requesting and abandoning audio focus.
	 */
	private static int ttsRequests;
	private float cSpeechRate = 1;
	private boolean speechAllowed = false;
	
	// Called from the calculating route thread.
	@Override
	public synchronized List<String> playCommands(CommandBuilder builder) {
		final List<String> execute = builder.execute(); //list of strings, the speech text, play it
		StringBuilder bld = new StringBuilder();
		for (String s : execute) {
			bld.append(s).append(' ');
		}
		sendAlertToPebble(bld.toString());
		if (mTts != null && !vrt.isMute() && speechAllowed) {
			if (ttsRequests++ == 0) {
				requestAudioFocus();
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
					mTts.setAudioAttributes(new AudioAttributes.Builder()
							.setUsage(ctx.getSettings().AUDIO_USAGE.get())
							.setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
							.build());
				}
				// Delay first prompt of each batch to allow BT SCO link being established, or when VOICE_PROMPT_DELAY is set >0 for the other stream types
				if (ctx != null) {
					Integer streamModeValue = ctx.getSettings().AUDIO_MANAGER_STREAM.getModeValue(getApplicationMode());
					OsmandPreference<Integer> pref = ctx.getSettings().VOICE_PROMPT_DELAY[streamModeValue];
					int vpd = pref == null ? 0 : pref.getModeValue(getApplicationMode());
					if (vpd > 0) {
						ttsRequests++;
						if (android.os.Build.VERSION.SDK_INT < 21) {
							params.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "" + System.currentTimeMillis());
							mTts.playSilence(vpd, TextToSpeech.QUEUE_ADD, params);
						} else {
							mTts.playSilentUtterance(vpd, TextToSpeech.QUEUE_ADD, "" + System.currentTimeMillis());
						}
					}
				}
			}
			log.debug("ttsRequests="+ttsRequests);
			params.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID,""+System.currentTimeMillis());
			mTts.speak(bld.toString(), TextToSpeech.QUEUE_ADD, params);
			// Audio focus will be released when onUtteranceCompleted() completed is called by the TTS engine.
		} else if (ctx != null && vrt.isMute()) {
			// sendAlertToAndroidWear(ctx, bld.toString());
		}
		// #5966: TTS Utterance for debugging
		if (ctx != null && ctx.getSettings().DISPLAY_TTS_UTTERANCE.get()) {
			((OsmandApplication) ctx.getApplicationContext()).showToastMessage(bld.toString());
		}
		return execute;
	}

	@Override
	public void stop(){
		ttsRequests = 0;
		if (mTts != null){
			mTts.stop();
		}
		abandonAudioFocus();
	}

	public void sendAlertToPebble(String bld) {
		final Intent i = new Intent("com.getpebble.action.SEND_NOTIFICATION");
		final Map<String, Object> data = new HashMap<String, Object>();
		data.put("title", "Voice");
		data.put("body", bld.toString());
		final JSONObject jsonData = new JSONObject(data);
		final String notificationData = new JSONArray().put(jsonData).toString();
		i.putExtra("messageType", PEBBLE_ALERT);
		i.putExtra("sender", "OsmAnd");
		i.putExtra("notificationData", notificationData);
		if (ctx != null) {
			ctx.sendBroadcast(i);
			log.info("Send message to pebble " + bld.toString());
		}
	}


	private void initializeEngine(final Context ctx, final Activity act) {
		if (mTtsContext != ctx) {
			internalClear();
		}
		if (mTts == null) {
			mTtsContext = ctx;
			ttsVoiceStatus = "-";
			ttsVoiceUsed = "-";
			ttsRequests = 0;
			final float speechRate = cSpeechRate;

			final String[] lsplit = (language + "____.").split("[\\_\\-]");
			// As per BCP 47: well formed scripts: [a-zA-Z]{4}, variants: [0-9][0-9a-zA-Z]{3} | [0-9a-zA-Z]{5,8}, countries/regions: [a-zA-Z]{2} | [0-9]{3}
			String lregion = "";
			String lvariant = "";
			String lscript = "";
			for (int i=3; i>0; i--) {
				if (lsplit[i].length() == 4 && !(lsplit[i] + "A").substring(0, 1).matches("[0-9]")) {
						lscript = lsplit[i];
				} else if (lsplit[i].length() >= 4) {
						lvariant = lsplit[i];
				} else {
						lregion = lsplit[i];
				}
			}
			// Locale constructor supports 'language, region, variant'
			//Locale newLocale0 = new Locale(lsplit[0], lregion, lvariant); (Setting variant here seems to cause errors on some systems)
			Locale newLocale0 = new Locale(lsplit[0], lregion);
			// #3344: Try Locale builder instead (only available from API 21), also supports script (we support as 4 letters)
			if (android.os.Build.VERSION.SDK_INT >= 21) {
				try {
					newLocale0 = new Locale.Builder().setLanguage(lsplit[0]).setScript(lscript).setRegion(lregion).setVariant(lvariant).build();
				} catch (RuntimeException e) {
					// Falls back to constructor
				}
			}
			final Locale newLocale = newLocale0;

			mTts = new TextToSpeech(ctx, new OnInitListener() {
				@Override
				public void onInit(int status) {
					if (status != TextToSpeech.SUCCESS) {
						ttsVoiceStatus = "NO INIT SUCCESS";
						internalClear();
						((OsmandApplication) ctx.getApplicationContext()).showToastMessage(ctx.getString(R.string.tts_initialization_error));
					} else if (mTts != null) {
						speechAllowed = true;
						switch (mTts.isLanguageAvailable(newLocale)) {
							case TextToSpeech.LANG_MISSING_DATA:
//								if (isSettingsActivity(act)) {
//									AlertDialog.Builder builder = createAlertDialog(
//										R.string.tts_missing_language_data_title,
//										R.string.tts_missing_language_data,
//										new IntentStarter(
//												act,
//												TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA),
//										act);
//									builder.show();
//								}
								ttsVoiceStatus = newLocale.getDisplayName() + ": LANG_MISSING_DATA";
								ttsVoiceUsed = getVoiceUsed();
								break;
							case TextToSpeech.LANG_AVAILABLE:
								ttsVoiceStatus = newLocale.getDisplayName() + ": LANG_AVAILABLE";
							case TextToSpeech.LANG_COUNTRY_AVAILABLE:
								ttsVoiceStatus = "-".equals(ttsVoiceStatus) ? newLocale.getDisplayName() + ": LANG_COUNTRY_AVAILABLE" : ttsVoiceStatus;
							case TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE:
								try {
									mTts.setLanguage(newLocale);
								} catch(Exception e) {
									e.printStackTrace();
									if (mTts.isLanguageAvailable(Locale.getDefault()) > 0) {
										mTts.setLanguage(Locale.getDefault());
									} else {
										Toast.makeText(act, "TTS language not available", Toast.LENGTH_LONG).show();
									}
								}
								if(speechRate != 1) {
									mTts.setSpeechRate(speechRate);
								}
								ttsVoiceStatus = "-".equals(ttsVoiceStatus) ? newLocale.getDisplayName() + ": LANG_COUNTRY_VAR_AVAILABLE" : ttsVoiceStatus;
								ttsVoiceUsed = getVoiceUsed();
								break;
							case TextToSpeech.LANG_NOT_SUPPORTED:
								//maybe weird, but I didn't want to introduce parameter in around 5 methods just to do this if condition
//								if (isSettingsActivity(act)) {
//									AlertDialog.Builder builder = createAlertDialog(
//											R.string.tts_language_not_supported_title,
//											R.string.tts_language_not_supported,
//											new IntentStarter(
//													act,
//													Intent.ACTION_VIEW, Uri.parse("market://search?q=text to speech engine"
//														)),
//											act);
//									builder.show();
//								}
								ttsVoiceStatus = newLocale.getDisplayName() + ": LANG_NOT_SUPPORTED";
								ttsVoiceUsed = getVoiceUsed();
								break;
						}
					}
				}

				private String getVoiceUsed() {
					try {
						if (android.os.Build.VERSION.SDK_INT >= 21) {
							if (mTts.getVoice() != null) {
								return mTts.getVoice().toString() + " (API " + android.os.Build.VERSION.SDK_INT + ")";
							}
						} else {
							return mTts.getLanguage() + " (API " + android.os.Build.VERSION.SDK_INT + " only reports language)";
						}
					} catch (RuntimeException e) {
						// mTts.getVoice() might throw NPE
					}
					return "-";
				}
			});
			mTts.setOnUtteranceCompletedListener(new OnUtteranceCompletedListener() {
				// The call back is on a binder thread.
				@Override
				public synchronized void onUtteranceCompleted(String utteranceId) {
					if (--ttsRequests <= 0)
						abandonAudioFocus();
					log.debug("ttsRequests="+ttsRequests);
					if (ttsRequests < 0) {
						ttsRequests = 0;
					}
				}
			});
		}
	}

	public static String getTtsVoiceStatus() {
		return ttsVoiceStatus;
	}

	public static String getTtsVoiceUsed() {
		return ttsVoiceUsed;
	}

	private void internalClear() {
		ttsRequests = 0;
		speechAllowed = false;
		if (mTts != null) {
			mTts.shutdown();
			mTts = null;
		}
		abandonAudioFocus();
		mTtsContext = null;
		ttsVoiceStatus = "-";
		ttsVoiceUsed = "-";
	}
	
	@Override
	public void clear() {
		super.clear();
		internalClear();
	}
	
	public static boolean isMyData(File voiceDir) {
		return new File(voiceDir, CONFIG_FILE).exists();
	}

	@Override
	public void updateAudioStream(int streamType) {
		super.updateAudioStream(streamType);
		params.put(TextToSpeech.Engine.KEY_PARAM_STREAM, streamType+"");		
	}

	@Override
	public boolean supportsStructuredStreetNames() {
		return getCurrentVersion() >= 103;
	}

}
