package com.buymed.rfidtag;

import android.app.Application;

/** Holds the singletons and starts loading the product table as soon as the process starts. */
public final class RfidApp extends Application {
    private ProductRepository repo;
    private ScanCache cache;
    private Settings settings;
    private Resolver resolver;

    @Override
    public void onCreate() {
        super.onCreate();
        repo = new ProductRepository();
        cache = new ScanCache(this);
        settings = new Settings(this);
        resolver = new Resolver(repo, cache, settings);
        repo.loadAsync(this);
    }

    public ProductRepository repo() { return repo; }
    public ScanCache cache() { return cache; }
    public Settings settings() { return settings; }
    public Resolver resolver() { return resolver; }
}
