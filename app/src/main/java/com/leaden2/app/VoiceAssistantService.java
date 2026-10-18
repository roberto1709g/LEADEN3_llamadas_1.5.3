package com.leaden2.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VoiceAssistantService extends Service implements TextToSpeech.OnInitListener {
    public static final String ACTION_PREPARE = "com.leaden2.app.voice.PREPARE";
    public static final String ACTION_LISTEN = "com.leaden2.app.voice.LISTEN";
    private static final String TAG = "LEADEN2Voice";
    private static final String CHANNEL_ID = "leaden2_voice_service";
    private static final int NOTIFICATION_ID = 2202;
    private static final String PREFS = "leaden2_local_data";
    private static final String WEBHOOK_URL = "https://n8n.serviapps.duckdns.org/webhook/asistente-leaden2";

    private SpeechRecognizer speechRecognizer;
    private TextToSpeech textToSpeech;
    private boolean ttsReady = false;
    private boolean listening = false;
    private boolean ttsBusy = false;
    private String pendingSpeech;
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        textToSpeech = new TextToSpeech(this, this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        promoteToForeground();
        String action = intent == null ? ACTION_PREPARE : intent.getAction();
        if (ACTION_LISTEN.equals(action)) startListening();
        return START_STICKY;
    }

    private void promoteToForeground() {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("LEADEN2 está activo")
            .setContentText("Servicio de voz preparado para las gafas")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            speak("Para usar el asistente, debes permitir el acceso al micrófono.");
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            speak("El reconocimiento de voz no está disponible en este teléfono.");
            return;
        }
        if (listening) {
            try { speechRecognizer.cancel(); } catch (Exception ignored) { }
            listening = false;
        }
        if (speechRecognizer != null) {
            try { speechRecognizer.destroy(); } catch (Exception ignored) { }
            speechRecognizer = null;
        }
        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) { Log.i(TAG, "Reconocimiento preparado."); }
                @Override public void onBeginningOfSpeech() { Log.i(TAG, "Usuario comenzó a hablar."); }
                @Override public void onRmsChanged(float rmsdB) { }
                @Override public void onBufferReceived(byte[] buffer) { }
                @Override public void onEndOfSpeech() { listening = false; }
                @Override public void onError(int error) {
                    listening = false;
                    Log.w(TAG, "Error de reconocimiento: " + error);
                    speak(errorMessage(error));
                }
                @Override public void onResults(Bundle results) {
                    listening = false;
                    ArrayList<String> matches = results == null ? null : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    String recognized = matches == null || matches.isEmpty() ? "" : matches.get(0).trim();
                    if (recognized.isEmpty()) speak("No logré entender la consulta. Inténtalo otra vez.");
                    else {
                        Log.i(TAG, "Consulta de voz reconocida; caracteres=" + recognized.length());
                        ActionType action = classify(recognized);
                        if (action == ActionType.CALL) {
                            // Interceptación local: las solicitudes de llamada no llegan a n8n.
                            Log.i(TAG, "Solicitud CALL interceptada localmente; no se enviará al webhook.");
                            speak("He detectado una solicitud de llamada. La búsqueda de contactos aún no está implementada.");
                        } else {
                            sendToWebhook(recognized);
                        }
                    }
                }
                @Override public void onPartialResults(Bundle partialResults) { }
                @Override public void onEvent(int eventType, Bundle params) { }
            });
            Intent recognitionIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            recognitionIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            recognitionIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-EC");
            recognitionIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "es-EC");
            recognitionIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
            recognitionIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            listening = true;
            speechRecognizer.startListening(recognitionIntent);
            Log.i(TAG, "Escucha iniciada.");
        } catch (Exception e) {
            listening = false;
            Log.e(TAG, "No se pudo iniciar el reconocimiento de voz.", e);
            speak("No pude iniciar el reconocimiento de voz. Comprueba el permiso del micrófono.");
        }
    }

    private void sendToWebhook(String recognizedText) {
        networkExecutor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
                JSONObject profile = new JSONObject(prefs.getString("profile", "{}"));
                if (profile.optString("nombre").trim().isEmpty()
                    || profile.optString("cedula").trim().isEmpty()
                    || profile.optString("celular").trim().isEmpty()
                    || profile.optString("correo").trim().isEmpty()) {
                    speak("Completa y guarda los datos del usuario en LEADEN2 antes de hacer una consulta.");
                    return;
                }

                JSONObject payload = new JSONObject();
                payload.put("nombre", profile.optString("nombre"));
                payload.put("cedula", profile.optString("cedula"));
                payload.put("celular", profile.optString("celular"));
                payload.put("correo", profile.optString("correo"));
                payload.put("text", recognizedText);

                ActionType action = classify(recognizedText);
                if (action == ActionType.ANALYSIS) {
                    payload.put("analisis", "analisis");
                } else if (action == ActionType.MESSAGE) {
                    payload.put("contactos", new JSONArray(prefs.getString("contacts", "[]")));
                }

                URL url = new URL(WEBHOOK_URL);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setRequestProperty("Accept", "application/json");
                byte[] body = payload.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(body); }
                int status = connection.getResponseCode();
                InputStream stream = status >= 200 && status < 400 ? connection.getInputStream() : connection.getErrorStream();
                String response = readAll(stream);
                if (status < 200 || status >= 300) {
                    Log.e(TAG, "Webhook devolvió HTTP " + status);
                    speak("El servidor del asistente devolvió un error. Inténtalo más tarde.");
                    return;
                }
                String outputText = extractOutput(response);
                if (outputText.isEmpty()) {
                    Log.w(TAG, "La respuesta no contiene el campo output.");
                    speak("Recibí una respuesta del servidor, pero no encontré el texto para leer.");
                } else {
                    Log.i(TAG, "Respuesta del webhook recibida correctamente.");
                    speak(outputText);
                }
            } catch (Exception e) {
                Log.e(TAG, "Falló la comunicación con el webhook LEADEN2.", e);
                speak("No pude comunicarme con el asistente. Comprueba la conexión a internet.");
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private ActionType classify(String text) {
        String normalized = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "");
        String[] words = normalized.split("[^a-z0-9]+", -1);
        int callPosition = -1;
        int analysisPosition = -1;
        int messagePosition = -1;
        int wordCount = 0;
        for (String word : words) {
            if (word.isEmpty()) continue;
            wordCount++;
            if (wordCount > 3) break;
            if (callPosition < 0 && word.equals("llamar")) callPosition = wordCount;
            if (analysisPosition < 0 && word.equals("analisis")) analysisPosition = wordCount;
            if (messagePosition < 0 && word.equals("mensaje")) messagePosition = wordCount;
        }
        // CALL tiene prioridad si "llamar" aparece entre las tres primeras palabras.
        if (callPosition >= 0) return ActionType.CALL;
        // Si ambas palabras clave aparecen entre las tres primeras, se usa la que aparece antes.
        if (analysisPosition >= 0 && (messagePosition < 0 || analysisPosition < messagePosition)) return ActionType.ANALYSIS;
        if (messagePosition >= 0) return ActionType.MESSAGE;
        return ActionType.GENERAL;
    }

private String extractOutput(String response) throws JSONException {
    if (response == null || response.trim().isEmpty()) return "";

    Object parsed = new org.json.JSONTokener(response).nextValue();
    JSONObject object = null;

    if (parsed instanceof JSONArray) {
        JSONArray array = (JSONArray) parsed;
        if (array.length() > 0) {
            object = array.optJSONObject(0);
        }
    } else if (parsed instanceof JSONObject) {
        object = (JSONObject) parsed;
    }

    if (object == null) return "";

    // Acepta el formato actual del webhook y el formato anterior.
    String output = object.optString("texto", "").trim();

    if (output.isEmpty()) {
        output = object.optString("output", "").trim();
    }

    return output;
}

   
    private String readAll(InputStream input) throws Exception {
        if (input == null) return "";
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return out.toString("UTF-8");
        }
    }

    private String errorMessage(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO: return "Hubo un problema con el audio del micrófono.";
            case SpeechRecognizer.ERROR_CLIENT: return "Se interrumpió el reconocimiento de voz. Inténtalo otra vez.";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "Falta el permiso del micrófono.";
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "El servicio de reconocimiento necesita conexión a internet.";
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "No detecté una frase clara. Inténtalo otra vez.";
            default: return "El reconocimiento de voz falló. Inténtalo otra vez.";
        }
    }

    private void speak(String text) {
        if (text == null || text.trim().isEmpty()) return;
        String message = text.trim();
        mainHandler.post(() -> {
            pendingSpeech = message;
            if (ttsReady && textToSpeech != null) speakPending();
        });
    }

    private void speakPending() {
        if (!ttsReady || textToSpeech == null || pendingSpeech == null) return;
        String text = pendingSpeech;
        pendingSpeech = null;
        textToSpeech.setLanguage(new Locale("es", "EC"));
        textToSpeech.setSpeechRate(1.0f);
        if (Build.VERSION.SDK_INT >= 21) textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "leaden2-response");
        else textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null);
    }

    @Override
    public void onInit(int status) {
        ttsReady = status == TextToSpeech.SUCCESS;
        if (ttsReady) {
            textToSpeech.setLanguage(new Locale("es", "EC"));
            speakPending();
        } else Log.e(TAG, "No se pudo inicializar TextToSpeech.");
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Servicio de voz LEADEN2", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Mantiene preparado el asistente de voz para las gafas Bluetooth.");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        if (speechRecognizer != null) {
            try { speechRecognizer.cancel(); speechRecognizer.destroy(); } catch (Exception ignored) { }
            speechRecognizer = null;
        }
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
            textToSpeech = null;
        }
        networkExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private enum ActionType { CALL, ANALYSIS, MESSAGE, GENERAL }
}
