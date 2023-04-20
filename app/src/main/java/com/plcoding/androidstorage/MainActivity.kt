package com.plcoding.androidstorage

import android.Manifest
import android.R
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.launch
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.plcoding.androidstorage.databinding.ActivityMainBinding
import gun0912.tedimagepicker.builder.TedImagePicker
import gun0912.tedimagepicker.builder.type.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.*


class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private lateinit var internalStoragePhotoAdapter: InternalStoragePhotoAdapter
    private lateinit var externalStoragePhotoAdapter: SharedPhotoAdapter

    private lateinit var contentObserver: ContentObserver

    private var readPermissionGranted = false
    private var writePermissionGranted = false
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        internalStoragePhotoAdapter = InternalStoragePhotoAdapter {
            lifecycleScope.launch {
                val isDeleteSuccessfully = deletePhotoFromInternalStorage(it.name)
                if (isDeleteSuccessfully) {
                    loadPhotosFromInternalStorageToRecyclerView()
                    Toast.makeText(this@MainActivity, "Delete successfully", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Failed to delete photo", Toast.LENGTH_SHORT).show()
                }
            }

        }

        externalStoragePhotoAdapter = SharedPhotoAdapter(this){

        }

        setupExternalStorageRecyclerView()
        initContentObserver()
        permissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
                readPermissionGranted = permissions[Manifest.permission.READ_EXTERNAL_STORAGE] ?: readPermissionGranted
                writePermissionGranted = permissions[Manifest.permission.WRITE_EXTERNAL_STORAGE] ?: writePermissionGranted

                if (readPermissionGranted) {
                    loadPhotosFromExternalStorageToRecyclerView()
                } else {
                    Toast.makeText(this, "Can't read file without permission", Toast.LENGTH_SHORT)
                        .show()
                }
            }

        updateOrRequestPermission()

        val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicturePreview()) {
            lifecycleScope.launch {
                val isPrivate = binding.switchPrivate.isChecked
                val isSaveSuccessfully = when {
                    isPrivate -> savePhotoToInternalStorage(UUID.randomUUID().toString(), it)
                    writePermissionGranted -> savePhotoToExternalStorage(
                        UUID.randomUUID().toString(),
                        it
                    )
                    else -> false
                }
                if (isPrivate) {
                    loadPhotosFromInternalStorageToRecyclerView()
                }
                if (isSaveSuccessfully) {
                    loadPhotosFromInternalStorageToRecyclerView()
                    Toast.makeText(this@MainActivity, "Photo saved successfully", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Failed to save photo", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnTakePhoto.setOnClickListener {
            takePhoto.launch()
        }
        binding.btnPick.setOnClickListener {
            TedImagePicker.with(this)
                .showTitle(true)
                .title("Select Image or Video")
                .max(9,"Max Count")
                .dropDownAlbum()
                .imageCountTextFormat("%s")
                .mediaType(MediaType.IMAGE_AND_VIDEO)
                .startMultiImage{
                it.forEach { result ->
                    Log.d("image",result.toString())
                }
            }
        }

        setupInternalStorageRecyclerView()
       // loadPhotosFromInternalStorageToRecyclerView()
      //
     // loadPhotosFromExternalStorageToRecyclerView()
    }

    private fun initContentObserver() {
        contentObserver = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                if (readPermissionGranted) {
                    loadPhotosFromExternalStorageToRecyclerView()
                }
            }
        }
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true,
            contentObserver
        )
    }

    private fun setupInternalStorageRecyclerView() = binding.rvPrivatePhotos.apply {
        adapter = internalStoragePhotoAdapter
        layoutManager = StaggeredGridLayoutManager(3, RecyclerView.VERTICAL)
    }

    private fun setupExternalStorageRecyclerView() = binding.rvPublicPhotos.apply {
        adapter = externalStoragePhotoAdapter
        layoutManager = StaggeredGridLayoutManager(3, RecyclerView.VERTICAL)
    }


    // request permission
    private fun updateOrRequestPermission() {
        var hasReadPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED

        var hasWritePermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED

        val minSdk29 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        readPermissionGranted = hasReadPermission
        writePermissionGranted = hasWritePermission || minSdk29

        val permissionToRequest = mutableListOf<String>()
        if (!writePermissionGranted) {
            permissionToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (!readPermissionGranted) {
            permissionToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (permissionToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionToRequest.toTypedArray())
        }
    }


    //
    private suspend fun savePhotoToExternalStorage(fileName: String, bitmap: Bitmap?): Boolean {
        return withContext(Dispatchers.IO) {
            val imageCollection = sdk29AndAbove {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } ?: MediaStore.Images.Media.EXTERNAL_CONTENT_URI

            val contentValue = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$fileName.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.WIDTH, bitmap?.width)
                put(MediaStore.Images.Media.HEIGHT, bitmap?.height)
            }
            try {
                contentResolver.insert(imageCollection, contentValue)?.also { uri ->
                    contentResolver.openOutputStream(uri).use { outputStream ->
                        bitmap?.let {
                            if (!it.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)) {
                                throw IOException("Couldn't save to bitmap")
                            }
                        }

                    }
                } ?: throw IOException("Couldn't create Mediastore entry")
                true
            } catch (e: IOException) {
                e.printStackTrace()
                false
            }
        }

    }


    // load photo from externalStorage
    private suspend fun loadPhotoFromExternalStorage(): List<SharedStoragePhoto> {
        return withContext(Dispatchers.IO) {
            val collection = sdk29AndAbove {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } ?: MediaStore.Images.Media.EXTERNAL_CONTENT_URI

            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT,
            )

            val photos = mutableListOf<SharedStoragePhoto>()
            contentResolver.query(
                collection,
                projection,
                null, null, "${MediaStore.Images.Media.DISPLAY_NAME} ASC"
            )?.use {
                val idColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val displayNameColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val widthColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val heightColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)

                while (it.moveToNext()) {
                    val id = it.getLong(idColumn)
                    val displayName = it.getString(displayNameColumn)
                    val width = it.getInt(widthColumn)
                    val height = it.getInt(heightColumn)
                    val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    photos.add(SharedStoragePhoto(id, displayName, width, height, contentUri))
                }
                photos.toList()
            } ?: listOf()
        }
    }

    private fun loadPhotosFromInternalStorageToRecyclerView() {
        lifecycleScope.launch {
            val photos = loadPhotoFromInternalStorage()
            //internalStoragePhotoAdapter.submitList(photos)
        }
    }

    private fun loadPhotosFromExternalStorageToRecyclerView() {
        lifecycleScope.launch {
            val photos = loadPhotoFromExternalStorage()
           // externalStoragePhotoAdapter.submitList(photos)
        }
    }


    private suspend fun loadPhotoFromInternalStorage(): List<InternalStoragePhoto> {
        return withContext(Dispatchers.IO) {
            val files = filesDir.listFiles()
            files?.filter { it.canRead() && it.isFile && it.name.endsWith(".jpg") }?.map {
                val bytes = it.readBytes()
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                InternalStoragePhoto(it.name, bmp)
            } ?: listOf()
        }
    }

    private suspend fun savePhotoToInternalStorage(fileName: String, bmp: Bitmap?): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                openFileOutput("$fileName.jpg", MODE_PRIVATE).use { stream ->
                    bmp?.let {
                        if (it.compress(Bitmap.CompressFormat.JPEG, 95, stream)) {
                            throw IOException("Couldn't save bitmap.")
                        }
                    }
                }
                true
            } catch (e: IOException) {
                e.printStackTrace()
                false
            }
        }
    }

    private suspend fun deletePhotoFromInternalStorage(fileName: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                deleteFile(fileName)
            } catch (e: java.lang.Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        contentResolver.unregisterContentObserver(contentObserver)
    }
}