/*
 * System-level patch in system_server:
 * Hook Location.writeToParcel to send mock=false to ALL receiving processes.
 * When Location is parceled (Binder IPC to any app), mock flag is cleared.
 */
Java.perform(function() {

    var Location = Java.use("android.location.Location");
    var Parcel = Java.use("android.os.Parcel");

    // Hook isFromMockProvider in this process
    Location.isFromMockProvider.implementation = function() { return false; };

    try {
        Location.isMock.implementation = function() { return false; };
    } catch(e) {}

    // Hook writeToParcel — clear mock flag before writing to IPC parcel
    // This means when any app reads the Location object, mock = false
    Location.writeToParcel.implementation = function(parcel, flags) {
        // Temporarily clear mock flag
        try {
            this.setIsFromMockProvider(false);
        } catch(e) {}
        return this.writeToParcel(parcel, flags);
    };
    console.log("[SysPatch] Location.writeToParcel hooked — mock cleared in IPC");

    // Also hook setTestProviderLocation to clear the flag at injection point
    try {
        var LM = Java.use("android.location.LocationManager");
        LM.setTestProviderLocation.implementation = function(provider, loc) {
            try { loc.setIsFromMockProvider(false); } catch(e) {}
            return this.setTestProviderLocation(provider, loc);
        };
        console.log("[SysPatch] setTestProviderLocation — mock cleared");
    } catch(e) {}

    console.log("[SysPatch] Ready. All locations will appear non-mock to all apps.");
});
