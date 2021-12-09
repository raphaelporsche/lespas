![Feature graphic](fastlane//metadata/android/en-US/images/featureGraphic.png)

## A photo album that saves and shares all your precious memory in the private Nextcloud server
<a href='https://play.google.com/store/apps/details?id=site.leos.apps.lespas'><img alt='Get it on Google Play' src='https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png' height='50'/></a>
<a href='https://f-droid.org/packages/site.leos.apps.lespas/'><img alt='Get it on F-Droid' src='https://fdroid.gitlab.io/artwork/badge/get-it-on.png' height='50'></a>

Les Pas, is a free, modern, lightweight and fast gallery app. Organize your photos, GIFs and videos into albums for easy viewing and sharing. With built-in two-way sync with your Nextcloud server, your files are kept private, secure and safe.

Features:
- Simple, beautiful and fast photos & videos viewing
- Organized photos and videos in albums
- Manage your phone's camera roll and auto backup to server
- Synchronization works with Nextcloud server and among multiple devices, edit albums on Nextcloud server and on all your mobile devices simultaneously
- Share albums with other Nextcloud users
- Joint album which you and other Nextcloud users can edit together
- Search for photos by objects with AI
- Search photos by location, show album in map (map data provided by <a href=https://www.openstreetmap.org>OpenStreetMap</a>)
- Integrate with Snapseed for photo editing on mobile devices
- Integrate with <a href=https://github.com/muzei/muzei>Muzei Live Wallpaper</a> app
- Share to social networks，option provided to strip photo's EXIF before sharing
- Theme design inspired by Wes Anderson's works
- All files saved in App's private storage, stop being scanned by malicious apps
- Open-source

<p float="left">
  <img alt="Welcom Page" src="fastlane/metadata/android/en-US/images/phoneScreenshots/1_Welcome.png" width="200" />
  <img alt="Album List" src="fastlane/metadata/android/en-US/images/phoneScreenshots/2_album.png" width="200" /> 
  <img alt="Album Detail" src="fastlane//metadata/android/en-US/images/phoneScreenshots/3_album_detail.png" width="200" />
  <img alt="Photo Viewer" src="fastlane//metadata/android/en-US/images/phoneScreenshots/4_photo.png" width="200" />
</p>

<p float="left">
  <img alt="Adding Photo" src="fastlane/metadata/android/en-US/images/phoneScreenshots/5_adding_photos.png" width="200" />
  <img alt="Browing Camera Roll" src="fastlane/metadata/android/en-US/images/phoneScreenshots/6_camera_roll.png" width="200" /> 
  <img alt="Publishing Album" src="fastlane//metadata/android/en-US/images/phoneScreenshots/7_publish.png" width="200" />
  <img alt="Search by Objects" src="fastlane//metadata/android/en-US/images/phoneScreenshots/8_search.png" width="200" />
</p>

<p float="left">
  <img alt="Search by Location" src="fastlane//metadata/android/en-US/images/phoneScreenshots/9_by_location.png" width="200" />
  <img alt="Show Album in Map" src="fastlane//metadata/android/en-US/images/phoneScreenshots/10_in_map.png" width="200" />
  <img alt="Show Photo with Map" src="fastlane//metadata/android/en-US/images/phoneScreenshots/11_with_map.png" width="200" />
</p>

This project build using the following open source software:
- <a href=https://square.github.io/okhttp>OkHttp</a>
- <a href=https://github.com/chrisbanes/PhotoView>PhotoView</a>
- <a href=https://github.com/osmdroid/osmdroid>osmdroid</a>
- <a href=https://www.tensorflow.org>TensorFlow</a>
- <a href=https://www.openstreetmap.org>OpenStreetMap</a>

<a id="faq"></a>
## Faq
### Why organzied by album?
I believe when someone start searching his/her memory for a moment in the past, it's hard for him/her to recall the exact date or exact location, but rather easy to remember what was happening during that period of time, like kid's birthday or family trip to Paris. So organized photos by events is probably the best way for most people, therefore grouping photos by event into an album is the best choice.

### Why use folder but not tag to group photos?
Les Pas use folder to group photos on the server, e.g., each album in Les Pas app has a one to one relationship with a folder on your Nextcloud server. You can manage your photo collection by working with folders/files on server side or albums/photos on your phone, Les Pas will sync changes from both sides. But how about tags? Yes, tagging is much more flexible than folder, and Nextcloud has it's own file tagging support too. But not every picture format support tagging, that makes tagging picture file a feature that will heavily rely on platform speicific functions. I would like my data (and yours too) to be platform neutual instead.

### Why does Les Pas use a lot of storage space?
Les Pas store photos in it's app private storage, so if you have a large collection of photos, you will find that it use a lot of storage space (I have 10GB myself) in Android's setting menu.<br> 
There are two reasons why Les Pas use private storage. First, Android introduced scope storage policy recently, highly recommends apps to stay out of share storage area. Second, storing photos in apps private storage area can prevent malicious apps scanning, uploading your photo secretly in the backgroud. Yes, they love your pictures so much, especially those with your face in it.<br>
**For privacy sake, stop using "/Pictures" folder in your phone's internal/external storage.**<br><br>
Since Les Pas use app's private storage to stop photos, if you reinstall the app, albums/photos need to be downloaded again from server.

### About server using self-signed certificate
You need to install your certificates in your phone first. A quick search on instructions points to <a href=https://aboutssl.org/how-to-create-and-import-self-signed-certificate-to-android-device/>here</a> and <a href=https://proxyman.io/blog/2020/09/Install-And-Trust-Self-Signed-Certificate-On-Android-11.html>here</a>.

### About synchronization
Les Pas does two types of sync in the background. A two-way sync of your albums and a one-way backup of your phone's camera roll.<br>
Whenever you did something with your albums on your phone, Les Pas will synchronize the changes to your server immediately. Since Nextcloud's push notification only work with Google Firebase Cloud Messaging, which Les Pas decided not to support due to privacy concern, any changes you make to your albums on server side will be synced to your phone during the next synchonization cycle.<br>
Upon opening Les Pas app, it will sync with server once. If you enable periodic sync setting, Les Pas will synchronize with your server every 6 hours in the background.<br>
One-way backup of phone's camera roll is a background job which also happen every 6 hours. So don't delete photos from your camera roll too fast too soon.
If synchronization doesn't seem to work, especially when you phone is a Chinese OEM model, like Huawei, Xiaomi, Oppo etc, please allow Les Pas app to auto start and opt-out battery optimization.
 
### Checklist for enabling sharing on Nextcloud server
To enable publishing (e.g. sharing album to other users on Nextcloud server), there are several things you need to take care beforehand:
- Make sure your are using LesPas version 2.0+
- Install and enable [Share Listing](https://apps.nextcloud.com/apps/sharelisting) on your Nextcloud server
- Set up groups on Nextcloud server and add users who wish to share LesPas albums to the group. User not belongs to any group can not download sharee list from server, this is a limitation of Nextcloud Sharee API
- Optionally, but highly recommended for the sake of smooth user experience, setup Nextcloud [Preview Generator](https://apps.nextcloud.com/apps/previewgenerator) app to automatically generate preview files of size 1024x1024, LesPas will use those files to populate shared album list on phone.
- Optionally, setup a specific "shared_with_me" folder to house all the shares you received, otherwise Nextcloud will dump all the shares you received onto your root folder. This can be done by adding line `'share_folder' => 'shared_with_me'` into Nextcloud's `config.php` file. Refer to nextcloud [document](https://docs.nextcloud.com/server/latest/admin_manual/configuration_server/config_sample_php_parameters.html) for detail.
