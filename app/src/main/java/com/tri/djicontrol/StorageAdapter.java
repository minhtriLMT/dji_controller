package com.tri.djicontrol;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;

import dji.sdk.media.MediaFile;

public class StorageAdapter extends BaseAdapter {

    private final Context context;
    private final List<MediaFile> mediaFiles;
    private final LayoutInflater inflater;

    public StorageAdapter(
            Context context,
            List<MediaFile> mediaFiles
    ) {
        this.context = context;
        this.mediaFiles = mediaFiles;
        this.inflater = LayoutInflater.from(context);
    }

    @Override
    public int getCount() {
        return mediaFiles.size();
    }

    @Override
    public Object getItem(int position) {
        return mediaFiles.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(
            int position,
            View convertView,
            ViewGroup parent
    ) {

        View view = inflater.inflate(
                R.layout.item_storage,
                parent,
                false
        );

        ImageView imgMedia =
                view.findViewById(R.id.imgMedia);

        TextView tvMediaType =
                view.findViewById(R.id.tvMediaType);

        TextView tvMediaName =
                view.findViewById(R.id.tvMediaName);

        TextView tvMediaDate =
                view.findViewById(R.id.tvMediaDate);

        MediaFile mediaFile =
                mediaFiles.get(position);

        // Tên file
        tvMediaName.setText(
                mediaFile.getFileName()
        );

        // Loại media
        if (mediaFile.getMediaType()
                == MediaFile.MediaType.MOV
                || mediaFile.getMediaType()
                == MediaFile.MediaType.MP4) {

            tvMediaType.setText("VIDEO");

        } else {

            tvMediaType.setText("ẢNH");
        }

        // Thời gian tạo thật của media trên DJI
        String dateCreated =
                mediaFile.getDateCreated();

        if (dateCreated != null
                && !dateCreated.isEmpty()) {

            try {

                SimpleDateFormat inputFormat =
                        new SimpleDateFormat(
                                "yyyy-MM-dd kk:mm:ss",
                                Locale.US
                        );

                SimpleDateFormat outputFormat =
                        new SimpleDateFormat(
                                "dd/MM/yyyy - HH:mm:ss",
                                Locale.US
                        );

                tvMediaDate.setText(
                        outputFormat.format(
                                inputFormat.parse(dateCreated)
                        )
                );

            } catch (Exception e) {

                tvMediaDate.setText(
                        dateCreated
                );
            }

        } else {

            tvMediaDate.setText(
                    "Không có thời gian"
            );
        }

        // Lấy thumbnail
        imgMedia.setImageResource(
                android.R.color.darker_gray
        );

        Bitmap thumbnail =
                mediaFile.getThumbnail();

        if (thumbnail != null) {

            imgMedia.setImageBitmap(
                    thumbnail
            );

        } else {

            imgMedia.setTag(mediaFile);

            mediaFile.fetchThumbnail(
                    error -> {

                        if (error == null) {

                            Bitmap bitmap =
                                    mediaFile.getThumbnail();

                            if (bitmap != null
                                    && mediaFile.equals(
                                    imgMedia.getTag())) {

                                imgMedia.post(() ->
                                        imgMedia.setImageBitmap(
                                                bitmap
                                        )
                                );
                            }
                        }
                    }
            );
        }
        view.setOnClickListener(v -> {

            if (mediaFile.getMediaType()
                    == MediaFile.MediaType.JPEG
                    || mediaFile.getMediaType()
                    == MediaFile.MediaType.TIFF) {

                Bitmap clickedThumbnail =
                        mediaFile.getThumbnail();

                if (clickedThumbnail != null) {

                    showImageDialog(
                            context,
                            clickedThumbnail,
                            mediaFile.getFileName()
                    );
                }
            }
        });

        return view;
    }
    private void showImageDialog(
            Context context,
            Bitmap bitmap,
            String fileName
    ) {

        android.app.Dialog dialog =
                new android.app.Dialog(context);

        android.widget.ImageView imageView =
                new android.widget.ImageView(context);

        imageView.setImageBitmap(bitmap);

        imageView.setScaleType(
                android.widget.ImageView.ScaleType.FIT_CENTER
        );

        imageView.setBackgroundColor(
                android.graphics.Color.BLACK
        );

        imageView.setOnClickListener(v ->
                dialog.dismiss()
        );

        dialog.setContentView(imageView);

        if (dialog.getWindow() != null) {

            dialog.getWindow().setBackgroundDrawableResource(
                    android.R.color.black
            );

            dialog.getWindow().setLayout(
                    (int) (
                            context.getResources()
                                    .getDisplayMetrics()
                                    .widthPixels * 0.90
                    ),
                    (int) (
                            context.getResources()
                                    .getDisplayMetrics()
                                    .heightPixels * 0.85
                    )
            );
        }

        dialog.show();

        if (dialog.getWindow() != null) {

            dialog.getWindow().setLayout(
                    (int) (
                            context.getResources()
                                    .getDisplayMetrics()
                                    .widthPixels * 0.90
                    ),
                    (int) (
                            context.getResources()
                                    .getDisplayMetrics()
                                    .heightPixels * 0.85
                    )
            );
        }
    }
}