package com.andrerinas.openheadunit;

import android.view.Surface;

// Vito fork: another app (our launcher) lends a Surface for the cluster picture. Not oneway: detach returns
// only when the decoder has let go of the Surface, so the caller may release it right after.
interface IClusterSurface {
    int version();
    // Frame width, height, then the content rectangle inside it: left, top, right, bottom (the margins are empty).
    int[] content();
    // Render into s from now on; client is the caller's own Binder, its death detaches.
    void attach(in Surface s, IBinder client);
    // By client and not by Surface: a Surface that crossed processes is a new object each time.
    void detach(IBinder client);
}
