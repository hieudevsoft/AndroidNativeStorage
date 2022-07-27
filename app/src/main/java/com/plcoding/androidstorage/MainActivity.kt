package com.plcoding.androidstorage

import android.Manifest
import android.app.RecoverableSecurityException
import android.app.usage.ExternalStorageStats
import android.content.ContentUris
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.launch
import androidx.activity.result.registerForActivityResult
import androidx.core.content.ContextCompat
import androidx.core.content.contentValuesOf
import androidx.lifecycle.coroutineScope
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.plcoding.androidstorage.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOError
import java.io.IOException

class MainActivity : AppCompatActivity() {
    private lateinit var binding:ActivityMainBinding
    private lateinit var internalStoragePhotoAdapter: InternalStoragePhotoAdapter
    private lateinit var externalStoragePhotoAdapter: SharedPhotoAdapter
    private var readPermissionGranted = false
    private var writePermissionGranted = false
    private lateinit var contentObserver:ContentObserver
    private lateinit var permissionLauncher:ActivityResultLauncher<Array<String>>
    private lateinit var intentSenderRequest:ActivityResultLauncher<IntentSenderRequest>
    private lateinit var deletedUriImage:Uri
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        internalStoragePhotoAdapter = InternalStoragePhotoAdapter {
            lifecycleScope.launch {
                if(deletePhotoFromInternalStorage(it.name)){
                    loadPhotosFromInternalStorageToRecyclerView()
                    Toast.makeText(this@MainActivity, "Photo deleted successfully", Toast.LENGTH_SHORT).show()
                }else{
                    Toast.makeText(this@MainActivity, "Photo deleted failure", Toast.LENGTH_SHORT).show()
                }
            }
        }
        externalStoragePhotoAdapter = SharedPhotoAdapter{
            lifecycleScope.launch {
                deletePhotoFromExternalStorage(it.contentUri)
                deletedUriImage = it.contentUri
            }
        }

        permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){
            readPermissionGranted = it[Manifest.permission.READ_EXTERNAL_STORAGE]?:readPermissionGranted
            writePermissionGranted = it[Manifest.permission.WRITE_EXTERNAL_STORAGE]?:writePermissionGranted
            if(readPermissionGranted){
                loadPhotosFromExternalStorageToRecyclerView()
            }
        }

        val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicturePreview()){
            lifecycleScope.launch {
                val isPrivate = binding.switchPrivate.isChecked
                val isSavedSuccessfully = when{
                    isPrivate->saveFileToInternalStorage(System.currentTimeMillis().toString(),it)
                    writePermissionGranted->savePhotoToExternalStorage(System.currentTimeMillis().toString(),it)
                    else -> false
                }
                if(isPrivate){
                    loadPhotosFromInternalStorageToRecyclerView()
                }
                if(isSavedSuccessfully){
                    Toast.makeText(this@MainActivity, "Photo saved successfully", Toast.LENGTH_SHORT).show()
                }else{
                    Toast.makeText(this@MainActivity, "Photo saved failure", Toast.LENGTH_SHORT).show()
                }
            }
        }
        binding.btnTakePhoto.setOnClickListener {
            takePhoto.launch()
        }
        initContentObserver()
        setupInternalStorageRecyclerView()
        loadPhotosFromInternalStorageToRecyclerView()
        loadPhotosFromExternalStorageToRecyclerView()
        updateRequestPermissions()
        intentSenderRequest = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()){
            if(it.resultCode== RESULT_OK){
                if(Build.VERSION_CODES.Q==Build.VERSION.SDK_INT){
                    lifecycleScope.launch {
                        deletePhotoFromExternalStorage(deletedUriImage)
                    }
                }
                Toast.makeText(this@MainActivity, "Delete saved successfully", Toast.LENGTH_SHORT).show()
            } else Toast.makeText(this@MainActivity, "Delete saved failure", Toast.LENGTH_SHORT).show()
        }
    }


    private fun <T> sdk29AndUp(onSdk29:()->T):T?{
        return if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q){
            onSdk29.invoke()
        } else null
    }

    private fun initContentObserver(){
        contentObserver = object:ContentObserver(null){
            override fun onChange(selfChange: Boolean) {
                if(readPermissionGranted) loadPhotosFromExternalStorageToRecyclerView()
            }
        }
        contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,true,contentObserver)
    }

    override fun onDestroy() {
        super.onDestroy()
        if(::contentObserver.isInitialized) contentResolver.unregisterContentObserver(contentObserver)
    }
    private suspend fun savePhotoToExternalStorage(displayName:String,bmp:Bitmap):Boolean{
        return withContext(Dispatchers.IO){
            val imageCollection = sdk29AndUp {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }?:MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val contentValues = contentValuesOf(
                MediaStore.Images.Media.DISPLAY_NAME to "$displayName.jpg",
                MediaStore.Images.Media.MIME_TYPE to "image/jpeg",
                MediaStore.Images.Media.WIDTH to bmp.width,
                MediaStore.Images.Media.HEIGHT to bmp.height,
            )
            try {
                contentResolver.insert(imageCollection,contentValues)?.also { uri->
                    contentResolver.openOutputStream(uri).use{
                        if(!bmp.compress(Bitmap.CompressFormat.JPEG,95,it)){
                            throw IOException("Couldn't save bitmap.")
                        }
                    }
                }?:throw  IOException("Couldn't create MediaStore entry")
                true
            }catch (e:Exception){
                e.printStackTrace()
                false
            }
        }
    }

    private fun updateRequestPermissions(){
        val hasReadPermission = ContextCompat.checkSelfPermission(this,Manifest.permission.READ_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED
        val hasWritePermission = ContextCompat.checkSelfPermission(this,Manifest.permission.WRITE_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED
        val minSdk29 = Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q
        readPermissionGranted = hasReadPermission
        writePermissionGranted = hasWritePermission||minSdk29

        val permissionsToRequest = mutableListOf<String>()
        if(!writePermissionGranted) permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if(!readPermissionGranted) permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        if(permissionsToRequest.isNotEmpty()){
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun setupInternalStorageRecyclerView(){
        binding.rvPrivatePhotos.apply {
            adapter = internalStoragePhotoAdapter
            layoutManager = StaggeredGridLayoutManager(3,RecyclerView.VERTICAL)
        }
        binding.rvPublicPhotos.apply {
            adapter = externalStoragePhotoAdapter
            layoutManager = StaggeredGridLayoutManager(3,RecyclerView.VERTICAL)
        }
    }

    private fun loadPhotosFromInternalStorageToRecyclerView(){
        lifecycle.coroutineScope.launchWhenStarted {
            val photos = loadPhotosFromInternalStorage()
            internalStoragePhotoAdapter.submitList(photos)
        }
    }

    private fun loadPhotosFromExternalStorageToRecyclerView(){
        lifecycle.coroutineScope.launchWhenStarted {
            val photos = loadPhotosFromExternalStorage()
            externalStoragePhotoAdapter.submitList(photos)
        }
    }

    private suspend fun deletePhotoFromInternalStorage(fileName:String):Boolean{
        return withContext(Dispatchers.IO){
            try {
                deleteFile(fileName)
            }catch (e:Exception){
                e.printStackTrace()
                false
            }
        }
    }

    private suspend fun deletePhotoFromExternalStorage(contentUri:Uri){
        return withContext(Dispatchers.IO){
            try {
                contentResolver.delete(contentUri,null,null)
            }catch (e:SecurityException){
                val intentSender = when{
                    Build.VERSION.SDK_INT>=Build.VERSION_CODES.R->{
                        MediaStore.createDeleteRequest(contentResolver, listOf(contentUri)).intentSender
                    }
                    Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q->{
                        val recoverableSecurityException = e as? RecoverableSecurityException
                        recoverableSecurityException?.userAction?.actionIntent?.intentSender
                    }
                    else->null
                }
                intentSender?.let {
                    intentSenderRequest.launch(IntentSenderRequest.Builder(it).build())
                }
            }
        }
    }

    private suspend fun loadPhotosFromExternalStorage():List<SharedStoragePhoto>{
        return withContext(Dispatchers.IO){
            val photos = mutableListOf<SharedStoragePhoto>()
            val imageCollection = sdk29AndUp {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }?:MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT,
            )
            contentResolver.query(imageCollection,projection,null,null,"${MediaStore.Images.Media.DISPLAY_NAME} ASC")?.use {
                cursor->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val displayNameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                cursor.moveToFirst()
                while (cursor.moveToNext()){
                    val id = cursor.getLong(idColumn)
                    val displayName = cursor.getString(displayNameColumn)
                    val width = cursor.getInt(widthColumn)
                    val height = cursor.getInt(heightColumn)
                    val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,id)
                    photos.add(SharedStoragePhoto(id,displayName,width,height,contentUri))
                }
            }
            photos.toList()
        }
    }

    private suspend fun loadPhotosFromInternalStorage():List<InternalStoragePhoto>{
        return withContext(Dispatchers.IO){
            filesDir.listFiles()?.filter { it.canRead()&&it.isFile&&it.name.endsWith(".jpg") }?.map {
                val bytes = it.readBytes()
                val bmp = BitmapFactory.decodeByteArray(bytes,0,bytes.size)
                InternalStoragePhoto(it.name,bmp)
            } ?: emptyList()
        }
    }

    private suspend fun <T> saveFileToInternalStorage(fileName:String,data:T):Boolean{
        return withContext(Dispatchers.IO){
            try {
                if(data is Bitmap){
                    openFileOutput("$fileName.jpg", MODE_PRIVATE).use {
                        val bmp = data as Bitmap
                        if(!bmp.compress(Bitmap.CompressFormat.JPEG,95,it)){
                            throw IOException("Couldn't save bitmap.")
                        }
                    }
                }else{
                    //Type Other
                }
                true
            }catch (e:Exception){
                e.printStackTrace()
                false
            }
        }
    }
}