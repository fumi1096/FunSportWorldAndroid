package com.funsportworld.android

import android.app.Application

/**
 * Application shell.
 *
 * Python is started lazily on a background thread by [com.funsportworld.android.backend.PythonBridge],
 * so the first frame of the Compose UI appears immediately instead of after the
 * Python runtime has finished unpacking its assets.
 */
class FunSportApp : Application()
