/* SPDX-License-Identifier: Apache-2.0
 *
 * Copyright © 2017-2021 Jason A. Donenfeld <Jason@zx2c4.com>. All Rights Reserved.
 */

#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

JNIEXPORT jstring JNICALL Java_org_amnezia_awg_GoBackend_awgBuildId(JNIEnv *env, jclass c)
{
    return (*env)->NewStringUTF(env, "PingNG-AWG-FIX15-PSIPHON-NETSTACK-20261005");
}

struct go_string { const char *str; long n; };
extern int awgTurnOn(struct go_string ifname, int tun_fd, struct go_string settings);
extern void awgTurnOff(int handle);
extern int awgGetSocketV4(int handle);
extern int awgGetSocketV6(int handle);
extern char *awgGetConfig(int handle);
extern char *awgVersion();

JNIEXPORT jint JNICALL Java_org_amnezia_awg_GoBackend_awgTurnOn(JNIEnv *env, jclass c, jstring ifname, jint tun_fd, jstring settings)
{
    __android_log_write(ANDROID_LOG_INFO, "AmneziaWG/JNI", "FIX14: entering isolated Go runtime");
	const char *ifname_str = (*env)->GetStringUTFChars(env, ifname, 0);
	size_t ifname_len = (*env)->GetStringUTFLength(env, ifname);
	const char *settings_str = (*env)->GetStringUTFChars(env, settings, 0);
	size_t settings_len = (*env)->GetStringUTFLength(env, settings);
	int ret = awgTurnOn((struct go_string){
		.str = ifname_str,
		.n = ifname_len
	}, tun_fd, (struct go_string){
		.str = settings_str,
		.n = settings_len
	});
	(*env)->ReleaseStringUTFChars(env, ifname, ifname_str);
	(*env)->ReleaseStringUTFChars(env, settings, settings_str);
	return ret;
}

JNIEXPORT void JNICALL Java_org_amnezia_awg_GoBackend_awgTurnOff(JNIEnv *env, jclass c, jint handle)
{
	awgTurnOff(handle);
}

JNIEXPORT jint JNICALL Java_org_amnezia_awg_GoBackend_awgGetSocketV4(JNIEnv *env, jclass c, jint handle)
{
	return awgGetSocketV4(handle);
}

JNIEXPORT jint JNICALL Java_org_amnezia_awg_GoBackend_awgGetSocketV6(JNIEnv *env, jclass c, jint handle)
{
	return awgGetSocketV6(handle);
}

JNIEXPORT jstring JNICALL Java_org_amnezia_awg_GoBackend_awgGetConfig(JNIEnv *env, jclass c, jint handle)
{
	jstring ret;
	char *config = awgGetConfig(handle);
	if (!config)
		return NULL;
	ret = (*env)->NewStringUTF(env, config);
	free(config);
	return ret;
}

JNIEXPORT jstring JNICALL Java_org_amnezia_awg_GoBackend_awgVersion(JNIEnv *env, jclass c)
{
	jstring ret;
	char *version = awgVersion();
	if (!version)
		return NULL;
	ret = (*env)->NewStringUTF(env, version);
	free(version);
	return ret;
}

extern int awgStartProxy(struct go_string settings, struct go_string addresses, struct go_string dns, int mtu, int psiphon_port);
extern int awgProxyPort(int handle, int final);
JNIEXPORT jint JNICALL Java_org_amnezia_awg_GoBackend_awgStartProxy(JNIEnv *env, jclass c, jstring settings, jstring addresses, jstring dns, jint mtu, jint psiphon_port)
{
 const char *s = (*env)->GetStringUTFChars(env, settings, 0);
 const char *a = (*env)->GetStringUTFChars(env, addresses, 0);
 const char *d = (*env)->GetStringUTFChars(env, dns, 0);
 int result = awgStartProxy((struct go_string){s, (*env)->GetStringUTFLength(env, settings)},
  (struct go_string){a, (*env)->GetStringUTFLength(env, addresses)},
  (struct go_string){d, (*env)->GetStringUTFLength(env, dns)}, mtu, psiphon_port);
 (*env)->ReleaseStringUTFChars(env, settings, s);
 (*env)->ReleaseStringUTFChars(env, addresses, a);
 (*env)->ReleaseStringUTFChars(env, dns, d);
 return result;
}
JNIEXPORT jint JNICALL Java_org_amnezia_awg_GoBackend_awgProxyPort(JNIEnv *env, jclass c, jint handle, jint final)
{
 return awgProxyPort(handle, final);
}
