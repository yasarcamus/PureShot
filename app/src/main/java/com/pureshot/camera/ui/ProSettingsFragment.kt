package com.pureshot.camera.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.fragment.app.Fragment
import com.pureshot.camera.databinding.FragmentProSettingsBinding

/**
 * ProSettingsFragment - AI işleme ve gelişmiş ayarlar
 */
class ProSettingsFragment : Fragment() {

    private var _binding: FragmentProSettingsBinding? = null
    private val binding get() = _binding!!

    // Settings state
    var hdrEnabled = true
    var portraitEnabled = true
    var denoiseEnabled = false
    var skinToneEnabled = true
    var rawEnabled = false
    var jpegQuality = 100

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUI()
    }

    private fun setupUI() {
        // HDR toggle
        binding.switchHdr.isChecked = hdrEnabled
        binding.switchHdr.setOnCheckedChangeListener { _, isChecked ->
            hdrEnabled = isChecked
        }

        // Portrait toggle
        binding.switchPortrait.isChecked = portraitEnabled
        binding.switchPortrait.setOnCheckedChangeListener { _, isChecked ->
            portraitEnabled = isChecked
        }

        // Denoise toggle
        binding.switchDenoise.isChecked = denoiseEnabled
        binding.switchDenoise.setOnCheckedChangeListener { _, isChecked ->
            denoiseEnabled = isChecked
        }

        // Skin tone toggle
        binding.switchSkinTone.isChecked = skinToneEnabled
        binding.switchSkinTone.setOnCheckedChangeListener { _, isChecked ->
            skinToneEnabled = isChecked
        }

        // RAW toggle
        binding.switchRaw.isChecked = rawEnabled
        binding.switchRaw.setOnCheckedChangeListener { _, isChecked ->
            rawEnabled = isChecked
        }

        // JPEG quality slider
        binding.seekBarJpegQuality.progress = jpegQuality
        binding.tvJpegQuality.text = "$jpegQuality%"
        binding.seekBarJpegQuality.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val quality = progress.coerceAtLeast(50)
                jpegQuality = quality
                binding.tvJpegQuality.text = "$quality%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
