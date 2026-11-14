package net.prototux.aare.audio;

/** Told by ShellAudioService when another app's recording that the car's mic should feed starts or stops. */
oneway interface IShellMicListener {
    void onPhoneAppsRecording(boolean recording) = 1;
}
