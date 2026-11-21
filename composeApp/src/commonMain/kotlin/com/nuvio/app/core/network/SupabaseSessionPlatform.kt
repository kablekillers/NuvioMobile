package com.nuvio.app.core.network

import io.github.jan.supabase.auth.SessionManager

/**
 * Where the login session is kept. null keeps supabase's default (Android, iOS).
 * Windows supplies its own store: the default there writes to a Java registry
 * location shared by every Java app on the PC, which could hand a fresh
 * install someone else's saved login.
 */
internal expect fun platformAuthSessionManager(): SessionManager?
