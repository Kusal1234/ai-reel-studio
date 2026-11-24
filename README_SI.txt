AI REEL STUDIO Android v1.1

v0.2 හි ක්‍රියාකාරී පහසුකම:
- Topic, language, duration සහ story style තෝරාගැනීම.
- Google Gemini API හරහා story + voice-over script + 6-10 scene descriptions
  + English image prompts + B-roll suggestions + camera/transition suggestions ලබාගැනීම.
- Generated script copy කිරීම.

වැදගත්:
- මෙය සම්පූර්ණ Reel Generator එකක් නොවේ. Image/Video/Voice generation සහ MP4 editing
  ඊළඟ versions වලදී සම්බන්ධ කරනු ඇත.
- Gemini API free tier එකේ models/requests/limits රට සහ කාලය අනුව වෙනස් විය හැක.
- API key එක app එකට ඇතුළත් කරන විට HTTPS භාවිත වේ. මෙම prototype එක key එක disk එකේ save නොකරයි.
  නමුත් production app එකක client-side API key එක සම්පූර්ණයෙන් රහසිගත නොවේ.
  පුද්ගලික/වැදගත් key භාවිත නොකරන්න; අවශ්‍ය නම් පසුව secure backend එකක් යොදමු.
- Google AI Studio වෙතින් API key එකක් සාදන්න: https://aistudio.google.com/app/apikey
- Free tier සහ data-use terms බලන්න: https://ai.google.dev/gemini-api/docs/pricing

APK build කිරීම — Android Studio නොමැතිව Cloud Build:
1. GitHub account එකක් අවශ්‍යයි.
2. මේ ZIP එක Extract කරන්න.
3. GitHub හි අලුත් repository එකක් සාදා Extract කළ folder එකේ files upload කරන්න.
4. Repository එකේ Actions tab එකට යන්න.
5. "Build AI Reel Studio APK" workflow එක තෝරා Run workflow ඔබන්න.
6. Build එක සාර්ථක වූ පසු Actions run එකේ Artifacts යටතේ
   "AI-Reel-Studio-v1.1-APK" download කරන්න.
7. ZIP එක ඇතුළේ app-debug.apk තිබේ. Phone එකට copy කර install කරන්න.
8. Android අවසරය ඉල්ලුවොත් ඔබ භාවිත කරන browser/file manager එකට Install unknown apps අවසරය දෙන්න.

Cloud build workflow එක .github/workflows/build-apk.yml තුළ තිබේ.
Android Studio දේශීයව install කිරීම අනිවාර්ය නැහැ.
