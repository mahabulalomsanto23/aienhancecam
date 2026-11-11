# AI Camera Pro — মডেল ও লাইসেন্স

## ব্যবহৃত মডেল
- **ML Kit Selfie Segmentation** (`com.google.mlkit:segmentation-selfie`): Portrait মোডে বিষয় আলাদা করতে এবং AI মোডে মানুষের ছবিতে subject-aware সংশোধনে। মডেলটি APK-এর ভেতরেই বান্ডেল করা, তাই সম্পূর্ণ অফলাইন কাজ করে। ব্যবহারের শর্ত: Google ML Kit Terms।

## ব্যবহার করা হয়নি
Real-ESRGAN, SwinIR, GFPGAN, CodeFormer, depth estimation, neural denoiser/scene classifier — এগুলো এই সংস্করণে নেই। AI মোডের বিশ্লেষণ, white balance, tone, রঙ, denoise ও sharpen প্রচলিত (classical) অ্যালগরিদম, ছবি-ভিত্তিক স্বয়ংক্রিয় প্যারামিটারসহ।

## বাকি লাইব্রেরি
AndroidX (CameraX, DataStore, AppCompat), Material Components, Kotlin Coroutines — সবই Apache 2.0।
