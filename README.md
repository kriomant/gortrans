# Gortrans

Android application which tracks location of public transport vehicles in Novosibirsk city (Russia). It works by using the same API http://maps.nskgortrans.ru site uses internally.

## Building

1. Install Android 4.0.3 (API 15) platform

1. Copy `android-app/user.sbt.template` to `android-app/user.sbt`
	and fill in required information:
	* path to keystore file
	* release key alias
	* Google Maps 

1. Execute

		# Specify path to Android SDK
		export ANDROID_HOME=/path/to/android/sdk
		
		# Create aliases for some tools (required by Android SBT plugin)
		ln -s ../build-tools/21.0.1/{aapt,dx} $ANDROID_HOME/platform-tools
		
		./sbt android-app/android:package-debug
