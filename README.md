# iLearn — منصة التعلّم الذاتي

Online courses and AI tutor setup: [usage guide](tasks/usage.md). Implementation and verification status: [evidence report](tasks/evidence/implementation.md).

[![Platform](https://img.shields.io/badge/Platform-Android%207.0%2B%20(API%2024%2B)-brightgreen.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.24-purple.svg)](android/build.gradle.kts)

**iLearn** تطبيق أندرويد للتعلّم الذاتي من ملفات الدروس المحلية، بواجهة عربية تدعم اتجاه اليمين إلى اليسار. يتيح استيراد الدورات من أرشيفات مضغوطة، ومشاهدة الفيديو مع الترجمة، وحفظ التقدم والملاحظات على الجهاز.

يحتوي هذا المستودع على الشيفرة المصدرية لتطبيق أندرويد وموارده وإعدادات بنائه. يبدأ التطبيق بمكتبة فارغة، ويضيف المستخدم محتواه من جهازه؛ لا توجد دورات أو ملفات وسائط تعليمية مرفقة بالمستودع.

## 👨‍💻 التطوير

**م. أمين علي | Eng. Ameen Ali**  
Lead AI Platform Architect & Developer

[LinkedIn](https://www.linkedin.com/in/eng-ameen-ali/) · [GitHub](https://github.com/ameen68-ali) · [Email](mailto:ameen68.ali@gmail.com)

## 🌟 ميزات التطبيق

### استيراد الدروس وتنظيمها

- استيراد حزم الدورات بصيغتي `.zip` و`.rar` من منتقي ملفات أندرويد.
- فهرسة فيديوهات `.mp4` وعرض الدورات ودروسها مع حالة الاستيراد.
- حفظ موضع المشاهدة، والدروس المكتملة، والعلامات المرجعية في قاعدة بيانات SQLite محلية.
- عرض إحصائيات التعلّم واستئناف آخر درس.

### تشغيل الفيديو والترجمة والصوت

- تشغيل الفيديو المحلي باستخدام AndroidX Media3 (ExoPlayer)، مع وضع ملء الشاشة والتحكم بسرعة التشغيل.
- اختيار الترجمة العربية أو الإنجليزية عند توفر ملفاتها، مع دعم `.vtt` و`.srt`.
- تغيير حجم خط الترجمة ومعاينته وحفظ التفضيل محلياً.
- إرفاق ملف صوت عربي خارجي والتبديل بينه وبين الصوت الأصلي.
- استخراج المسار الصوتي من الفيديو بصيغة `.m4a` ومشاركته عبر التطبيقات المتاحة على الجهاز.

### متابعة التعلّم

- إضافة ملاحظات مرتبطة بتوقيت محدد داخل الدرس.
- تصدير واستعادة التقدم والملاحظات بصيغة JSON.
- واجهة مبنية باستخدام Jetpack Compose وMaterial 3، مع مظهر فاتح وداكن ودعم RTL وتوصيفات عربية لعناصر التحكم.

## 📂 بنية المشروع

```text
i_learn_app/
├── .gitignore
├── README.md
└── android/
    ├── build.gradle.kts
    ├── settings.gradle.kts
    ├── gradle.properties
    ├── store/
    │   └── ilearn_icon_512.png
    └── app/
        ├── build.gradle.kts
        └── src/main/
            ├── AndroidManifest.xml
            ├── res/
            └── java/com/ilearn/app/
                ├── MainActivity.kt
                ├── data/
                │   ├── Models.kt
                │   ├── CourseRepository.kt
                │   └── CourseDatabaseHelper.kt
                ├── importer/
                │   └── CourseArchiveImporter.kt
                ├── player/
                │   └── ILearnPlayerController.kt
                ├── ui/
                │   ├── components/
                │   ├── screens/
                │   │   ├── CourseCatalogScreen.kt
                │   │   ├── CourseDetailScreen.kt
                │   │   └── VideoPlayerScreen.kt
                │   └── theme/
                └── utils/
                    └── AudioExtractorHelper.kt
```

## 🛠️ التقنيات ومتطلبات البناء

| المكوّن | الإعداد المستخدم |
| --- | --- |
| لغة البرمجة | Kotlin 1.9.24، مع Java/JVM 17 |
| Android Gradle Plugin | 8.5.0 |
| واجهة المستخدم | Jetpack Compose BOM 2024.05.00 وMaterial 3 |
| تشغيل الوسائط | AndroidX Media3 1.3.1 |
| التخزين المحلي | SQLite عبر `SQLiteOpenHelper` |
| قراءة الأرشيفات | `ZipInputStream` وJunrar 7.5.5 |
| الحد الأدنى لنظام التشغيل | Android 7.0، API 24 |
| Compile SDK / Target SDK | API 34 |

للبناء، يلزم JDK 17 وAndroid SDK 34 وSDK Build-Tools 34.0.0، مع Gradle 8.7 وفق [متطلبات Android Gradle Plugin 8.5](https://developer.android.com/build/releases/agp-8-5-0-release-notes#compatibility).

## 🚀 تشغيل المشروع

1. افتح مجلد `android/` في Android Studio.
2. اضبط Gradle JDK على JDK 17، وحدد مسار Android SDK محلياً عبر إعدادات البيئة أو ملف `android/local.properties`.
3. استخدم Gradle Wrapper المرفق (Gradle 8.12) مع JDK 17 ثم نفّذ Gradle Sync. يحتاج تنزيل الاعتماديات لأول مرة إلى اتصال بالإنترنت.
4. اختر نسخة `debug` وشغّل التطبيق على جهاز أو محاكي يعمل بنظام Android 7.0 أو أحدث.

يمكن أيضاً بناء نسخة التطوير من PowerShell إذا كان Gradle متاحاً في `PATH`:

```powershell
Set-Location -LiteralPath '.\android'
.\gradlew.bat :app:assembleDebug
```

ينتج ملف التثبيت في:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

لبناء نسخة `release` موقعة، ضع مفتاحك المحلي في `android/app/ilearn-release.jks` بالاسم المستعار `ilearn`، أو عدّل إعداد التوقيع ليتوافق مع مفتاحك. وفّر كلمتي المرور عبر متغيري البيئة `ILEARN_STORE_PASSWORD` و`ILEARN_KEY_PASSWORD`، أو عبر ملف `android/keystore.properties` محلي بهذه البنية:

```properties
storePassword=YOUR_LOCAL_STORE_PASSWORD
keyPassword=YOUR_LOCAL_KEY_PASSWORD
```

تأخذ متغيرات البيئة الأولوية عند ضبطها. مفتاح التوقيع وملف كلمات المرور مستبعدان من Git، ولا يلزمان لبناء نسخة `debug`.

## 📱 استخدام التطبيق

1. افتح التطبيق واضغط زر استيراد دورة، ثم اختر أرشيفاً من جهازك.
2. اختر ملف `.zip` أو `.rar` معدّاً وفق البنية الموضحة أدناه، ثم انتظر اكتمال الاستيراد وفهرسة الدروس.
3. افتح الدورة واختر درساً للمشاهدة أو لإضافة ملاحظة وعلامة مرجعية. يمكن إرفاق ملف صوت عربي من أدوات الصوت داخل المشغل.

## 📦 بنية ملفات ZIP وRAR القابلة للاستيراد

تنطبق البنية نفسها على `.zip` و`.rar`. جهّز أرشيفاً واحداً لكل دورة، مع فيديوهات الدروس والترجمات الاختيارية. لا يحتاج الاستيراد إلى ملف فهرس أو `manifest.json`.

### المجلدات مباشرة في جذر الأرشيف

```text
My_Course.zip
├── compressed_videos/
│   ├── 001_introduction.mp4
│   └── 002_first_lesson.mp4
├── subtitles_ar/
│   ├── 001_introduction.vtt
│   └── 002_first_lesson.srt
└── subtitles_en/
    ├── 001_introduction.vtt
    └── 002_first_lesson.srt
```

عند إعداد هذا الشكل، أضف المجلدات `compressed_videos/` و`subtitles_ar/` و`subtitles_en/` نفسها إلى الأرشيف، بحيث تظهر فور فتحه.

### المجلدات داخل مجلد دورة واحد

يمكن أيضاً وضع المجلدات داخل مجلد حاوٍ واحد، سواء كان الأرشيف ZIP أم RAR:

```text
My_Course.rar
└── My_Course/
    ├── compressed_videos/
    │   ├── 001_introduction.mp4
    │   └── 002_first_lesson.mp4
    ├── subtitles_ar/
    │   ├── 001_introduction.vtt
    │   └── 002_first_lesson.srt
    └── subtitles_en/
        ├── 001_introduction.vtt
        └── 002_first_lesson.srt
```

### قواعد إعداد الملفات

| العنصر | المطلوب |
| --- | --- |
| `compressed_videos/` | إلزامي، ويحتوي على فيديو واحد على الأقل بصيغة `.mp4` مباشرة داخله. |
| `subtitles_ar/` | اختياري، ويحتوي على ملفات الترجمة العربية بصيغة `.vtt` أو `.srt`. |
| `subtitles_en/` | اختياري، ويحتوي على ملفات الترجمة الإنجليزية بصيغة `.vtt` أو `.srt`. |
| أسماء المجلدات | استخدم الأسماء كما هي وبالأحرف الصغيرة، وضع مجلدي الترجمة بجانب `compressed_videos/`. |
| ترتيب الدروس | ابدأ أسماء الفيديوهات بأرقام متسلسلة وفريدة مثل `001_` و`002_`؛ يرتبها التطبيق حسب الرقم في بداية الاسم. |
| مطابقة الترجمة | اجعل اسم ملف الترجمة مطابقاً لاسم الفيديو قبل الامتداد، مثل `001_introduction.mp4` و`001_introduction.vtt`. |

إذا لم يجد التطبيق ترجمة بالاسم المطابق، يبحث عن ملف ترجمة يبدأ بالرقم نفسه. لتجنب الالتباس، استخدم اسماً مطابقاً ورقماً فريداً لكل درس. عند وجود ملفي `.vtt` و`.srt` بالاسم المطابق نفسه، يختار التطبيق `.vtt`.

لا تضع الفيديوهات داخل مجلدات فرعية إضافية تحت `compressed_videos/`، ولا تضع مجلد الدورة داخل أكثر من مستوى حاوٍ؛ لا يفحص المستورد هذه المستويات. كذلك لا تجمع عدة دورات مستقلة في أرشيف واحد، لأن المستورد يفهرس مجموعة دروس واحدة في كل عملية استيراد.

يمكن حذف أحد مجلدي الترجمة أو كليهما إذا لم تتوفر ترجمة. أما ملفات الدبلجة الصوتية فلا يربطها المستورد تلقائياً من الأرشيف؛ أرفق ملف الصوت العربي للدرس من داخل المشغل بعد الاستيراد.

## 🔒 التخزين والخصوصية

- يحفظ التطبيق الدورات المستوردة في مساحة ملفات خاصة بالتطبيق على الجهاز، والتقدم والملاحظات في قاعدة بيانات محلية.
- يستخدم منتقي ملفات أندرويد لاختيار الأرشيفات والصوت وملفات النسخ الاحتياطي، ويتيح مشاركة الصوت المستخرج عبر التطبيقات التي يختارها المستخدم.
- ملفات النسخ الاحتياطي تتضمن التقدم والملاحظات؛ لا تتضمن فيديوهات الدورات.

## 🤝 المساهمة

المشروع مفتوح للمساهمة من الجميع. نرحب بإصلاح الأخطاء، وتطوير ميزات التطبيق، وتحسين الواجهة العربية وإمكانية الوصول، وتحديث التوثيق.

1. للإبلاغ عن مشكلة أو اقتراح تحسين، افتح `Issue` في مستودع GitHub مع وصف واضح وخطوات إعادة ظهور المشكلة إن وجدت.
2. للمساهمة بتعديل، أنشئ نسخة `Fork` وفرعاً مخصصاً للتغيير، ثم نفّذ التعديل وتحقق منه.
3. أرسل `Pull Request` يوضح التغيير وسببه وكيفية التحقق منه.

## 📄 الحقوق — مشاع عام

هندسة وتطوير المنصة: المهندس **أمين علي (Eng. Ameen Ali)**.

الشيفرة الأصلية لهذا المشروع متاحة للمشاع العام (Public Domain)، ويمكن للجميع استخدامها ونسخها وتعديلها وإعادة توزيعها، بما في ذلك للأغراض التجارية. تبقى مكتبات الطرف الثالث خاضعة لتراخيصها الخاصة.
