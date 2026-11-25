package com.phonecleaner.ai;

interface IShellService {
    // Reserved transaction code used by Shizuku to tear the service down.
    void destroy() = 16777114;

    // Runs "sh -c command" and returns a pipe with merged stdout/stderr.
    // The stream ends with a line "__PCA_EXIT__:<exit code>".
    ParcelFileDescriptor run(String command) = 1;

    int uid() = 2;
}
