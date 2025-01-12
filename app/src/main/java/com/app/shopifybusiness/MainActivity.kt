package com.app.shopifybusiness

import android.annotation.SuppressLint
import android.content.Intent
import android.content.Intent.ACTION_GET_CONTENT
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.app.shopifybusiness.databinding.ActivityMainBinding
import com.app.shopifybusiness.models.Product
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.skydoves.colorpickerview.ColorEnvelope
import com.skydoves.colorpickerview.ColorPickerDialog
import com.skydoves.colorpickerview.listeners.ColorEnvelopeListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private val binding by lazy { ActivityMainBinding.inflate(layoutInflater) }
    private val selectedColors = mutableListOf<Int>()
    private var selectedImages = mutableListOf<Uri>()
    private val firestore = FirebaseFirestore.getInstance()
    private val productStorage = FirebaseStorage.getInstance().reference

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        // Initialize Firebase
        FirebaseApp.initializeApp(this)

        // Color picker dialog
        binding.btnColorPicker.setOnClickListener {
            ColorPickerDialog
                .Builder(this)
                .setTitle("Product Color")
                .setPositiveButton("Select", object : ColorEnvelopeListener {
                    override fun onColorSelected(envelope: ColorEnvelope?, fromUser: Boolean) {
                        envelope?.let {
                            selectedColors.add(it.color)
                            updateColors()
                        }
                    }
                })
                .setNegativeButton("Cancel") { colorPicker, _ ->
                    colorPicker.dismiss()
                }.show()
        }

        // Image picker
        val selectImagesActivityResult =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    val intent = result.data
                    // Multiple images selected
                    if (intent?.clipData != null) {
                        val count = intent.clipData?.itemCount ?: 0
                        (0 until count).forEach {
                            val imageUri = intent.clipData?.getItemAt(it)?.uri
                            imageUri?.let {
                                selectedImages.add(it)
                            }
                        }
                    } else {
                        val imageUri = intent?.data
                        imageUri?.let {
                            selectedImages.add(it)
                        }
                    }
                    updateImages()
                }
            }

        binding.btnImagesPicker.setOnClickListener {
            val intent = Intent(ACTION_GET_CONTENT)
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            intent.type = "image/*"
            selectImagesActivityResult.launch(intent)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.toolbar_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.upload_product) {
            // Handle upload product action
            val productValidation = validateProduct()
            if (!productValidation) {
                Toast.makeText(this, "Check your product details!!!", Toast.LENGTH_SHORT).show()
                return false
            }
            saveProduct { success ->
                if (!success) {
                    Log.d("ERROR", "Product save failed!")
                }
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun validateProduct(): Boolean {
        if (binding.edtPrice.text.toString().trim().isEmpty()) return false
        if (binding.edtName.text.toString().trim().isEmpty()) return false
        if (binding.edtCategory.text.toString().trim().isEmpty()) return false
        if (selectedImages.isEmpty()) return false
        return true
    }

    private fun saveProduct(state: (Boolean) -> Unit) {
        val name = binding.edtName.text.toString().trim()
        val category = binding.edtCategory.text.toString().trim()
        val price = binding.edtPrice.text.toString().trim()
        val offerPercentage = binding.edtOfferPercentage.text.toString().trim()
        val description = binding.edtDescription.text.toString().trim()
        val sizes = getSizesList(binding.edtSizes.text.toString().trim())
        val images = mutableListOf<String>()

        lifecycleScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                showLoading()
            }
            try {
                val uploadJobs = mutableListOf<Job>()
                selectedImages.forEach { uri ->
                    val uploadJob = launch {
                        try {
                            val id = UUID.randomUUID().toString()
                            val imageStorage = productStorage.child("products/images/$id")
                            val byteArray = getBitmapByteArray(uri)
                            Log.d("TAG", "Image ByteArray size: ${byteArray.size}")

                            val result = imageStorage.putBytes(byteArray).await()
                            val downloadUrl = result.storage.downloadUrl.await().toString()
                            images.add(downloadUrl)
                            Log.d("TAG", "Image uploaded: $downloadUrl")
                        } catch (e: Exception) {
                            Log.e("ImageUpload", "Error uploading image: $uri", e)
                        }
                    }
                    uploadJobs.add(uploadJob)
                }
                uploadJobs.joinAll()

                // Log final data before saving
                Log.d(
                    "TAG",
                    "Product Data: Name=$name, Category=$category, Price=$price, Colors=$selectedColors, Images=$images"
                )

                val product = Product(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    category = category,
                    price = price.toFloat(),
                    offerPercentage = if (offerPercentage.isEmpty()) null else offerPercentage.toFloat(),
                    description = if (description.isEmpty()) null else description,
                    colors = if (selectedColors.isEmpty()) null else selectedColors,
                    sizes = sizes,
                    images = images
                )

                firestore.collection("products").add(product).addOnSuccessListener {
                    lifecycleScope.launch(Dispatchers.Main) {
                        Toast.makeText(
                            this@MainActivity,
                            "Product uploaded successfully!!!",
                            Toast.LENGTH_SHORT
                        ).show()
                        clearFields()
                    }
                    state(true)
                    hideLoading()
                }.addOnFailureListener {
                    lifecycleScope.launch(Dispatchers.Main) {
                        Toast.makeText(
                            this@MainActivity,
                            "Something went wrong!!!",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    state(false)
                    hideLoading()
                    Log.e("ERROR", it.message.toString())
                }
            } catch (e: Exception) {
                Log.e("ERROR", e.message.toString())
                withContext(Dispatchers.Main) {
                    hideLoading()
                }
                state(false)
            }
        }
    }


    private fun clearFields() {
        binding.edtName.text.clear()
        binding.edtCategory.text.clear()
        binding.edtPrice.text.clear()
        binding.edtOfferPercentage.text.clear()
        binding.edtDescription.text.clear()
        binding.edtSizes.text.clear()
        selectedImages.clear()
        selectedColors.clear()
        updateImages()
        updateColors()
    }

    private fun hideLoading() {
        binding.progressBar.visibility = View.INVISIBLE
    }

    private fun showLoading() {
        binding.progressBar.visibility = View.VISIBLE
    }

    private fun getBitmapByteArray(uri: Uri): ByteArray {
        val inputStream = contentResolver.openInputStream(uri)
        val bitmap = BitmapFactory.decodeStream(inputStream)
        val scaledBitmap =
            Bitmap.createScaledBitmap(bitmap, 800, 800, false) // Scale image if too large
        val stream = ByteArrayOutputStream()
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
        return stream.toByteArray()
    }

    private fun getSizesList(sizesStr: String): List<String>? {
        if (sizesStr.isEmpty()) return null
        val sizes = sizesStr.split(",")
        return sizes.map { it.trim() }
    }

    @SuppressLint("SetTextI18n")
    private fun updateImages() {
        binding.tvSelectedImages.text = selectedImages.size.toString()
    }

    private fun updateColors() {
        var colors = ""
        selectedColors.forEach {
            colors = "$colors ${Integer.toHexString(it)}"
        }
        binding.tvSelectedColors.text = colors
    }
}