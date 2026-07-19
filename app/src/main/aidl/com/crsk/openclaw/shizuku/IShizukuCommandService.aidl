package com.crsk.openclaw.shizuku;

interface IShizukuCommandService {
    String exec(in String[] argv, in String stdin, int timeoutMs);
    String version();
    void destroy();
}
