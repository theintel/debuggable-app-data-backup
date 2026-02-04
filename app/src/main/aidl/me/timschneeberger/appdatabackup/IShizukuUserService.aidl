// IShizukuUserService.aidl
package me.timschneeberger.appdatabackup;

import me.timschneeberger.appdatabackup.shizuku.ShellResult;

interface IShizukuUserService {
    void destroy() = 16777114; // Destroy method defined by Shizuku server

    boolean canUseRunAs() = 1;

    ShellResult run(String command) = 2;
}