# Gortrans

Android application which tracks location of public transport vehicles in Novosibirsk city (Russia). It works by using the same API [nskgortrans](https://maps.nskgortrans.ru) site uses internally.

## Building

1. Install Android SDK platform (API 19)
2. Replace 'API_KEY' in string 'google_maps_key' in 'strings' resource with your Google Maps API key
3. Run ```gradlew :app:packageDebug```
