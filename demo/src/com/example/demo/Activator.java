package com.example.demo;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

public class Activator implements BundleActivator {

	public void start(BundleContext arg0) throws Exception {
		System.err.println("Hello World");
	}

	public void stop(BundleContext arg0) throws Exception {
		System.err.println("Goodbye World");
	}

}
