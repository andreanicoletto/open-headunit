package com.andrerinas.openheadunit;

import android.view.Surface;

// Vito fork: another app (our launcher) lends a Surface for the cluster picture. attach and detach are oneway (version 2):
// the caller is a home screen and must never wait for our decoder. It keeps its Surface alive for a moment after detach.
interface IClusterSurface {
    int version();
    // Frame width, height, then the content rectangle inside it: left, top, right, bottom (the margins are empty).
    int[] content();
    // Render into s from now on; client is the caller's own Binder, its death detaches.
    oneway void attach(in Surface s, IBinder client);
    // By client and not by Surface: a Surface that crossed processes is a new object each time.
    oneway void detach(IBinder client);
}
