package weiner.noah.usbscratch;

public interface IUsbConnectionHandler {
    void onUsbStopped();

    void onErrorLooperRunningAlready();

    void onDeviceNotFound();
}
