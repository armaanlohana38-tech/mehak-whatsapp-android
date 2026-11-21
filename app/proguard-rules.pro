# Keep the JavaScript bridge methods reachable from WebView.
-keepclassmembers class com.armaanlohana38.mehakwhatsapp.MainActivity$SpeechBridge {
    public *;
}
-keepclassmembers class com.armaanlohana38.mehakwhatsapp.MainActivity$TtsBridge {
    public *;
}
