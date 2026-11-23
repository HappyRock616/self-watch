# Self Watch link client (Apache-2.0). Merged into the consuming app's R8 configuration.
# The AIDL interfaces and their generated Stub/Proxy classes are the IPC contract with Self Watch.
-keep interface app.selfwatch.link.ILinkService { *; }
-keep class app.selfwatch.link.ILinkService$** { *; }
-keep interface app.selfwatch.link.ILinkCallback { *; }
-keep class app.selfwatch.link.ILinkCallback$** { *; }
