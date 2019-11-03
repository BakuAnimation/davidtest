use std::path::Path;
use tokio_executor::blocking;
use tokio::fs::{create_dir, File};
use tokio::prelude::*;
use warp::Filter;
use uuid::Uuid;
use serde::Deserialize;


#[derive(Deserialize)]
struct Size {
    width: u32,
    height: u32
}

async fn handle_multipart(mut form: warp::multipart::FormData) -> Result<Vec<Uuid>, warp::Rejection> {
    let img_directory = Path::new("upload_files");
    if !img_directory.exists() {
        create_dir(img_directory).await.map_err(warp::reject::custom)?;
    }

    let mut uuids = Vec::new();

    while let Some(part) = form.next().await {
        let part = part.map_err(warp::reject::custom)?;
        
        let uuid = Uuid::new_v4();
        let filename = format!("{}.jpg", uuid.to_string());
        let image_path = Path::join(img_directory, Path::new(&filename));

        let image_buffer = part.concat().await;

        let mut image_file = File::create(image_path)
            .await
            .map_err(warp::reject::custom)?;
        image_file.write_all(&image_buffer).await.map_err(warp::reject::custom)?;
        image_file.sync_data().await.map_err(warp::reject::custom)?;

        uuids.push(uuid);
    }

    Ok(uuids)
}


async fn handle_get_images(filename: String, r : Size) -> Result<Vec<u8>, warp::Rejection> {
    let thumbs_directory = Path::new("upload_thumbs");
    let thumbnail_path = Path::join(thumbs_directory, Path::new(&format!("{}-{}x{}", filename, r.width, r.height)));

    let mut thumbnail_buffer = Vec::new();

    if thumbnail_path.exists() {
        let mut f = File::open(&thumbnail_path).await.map_err(warp::reject::custom)?;
        f.read_to_end(&mut thumbnail_buffer).await.map_err(warp::reject::custom)?;
    } else {
        let img_directory = Path::new("upload_files");
        let image_path = Path::join(img_directory, Path::new(&filename));
        let image = image::open(&image_path).map_err(warp::reject::custom)?;

        println!("Resizing {}", filename);
        let thumbnail = blocking::run(move || {
            image.resize(r.width, r.height, image::imageops::FilterType::Lanczos3)
        })
        .await;
        println!("Done {}", filename);

        if !thumbs_directory.exists() {
            create_dir(thumbs_directory).await.map_err(warp::reject::custom)?;
        }

        thumbnail
            .write_to(&mut thumbnail_buffer, image::ImageOutputFormat::JPEG(200))
            .map_err(warp::reject::custom)?;

        let mut thumb_file = File::create(thumbnail_path)
            .await
            .map_err(warp::reject::custom)?;
        let thumb_fut = async {
            thumb_file.write_all(&thumbnail_buffer).await?;
            thumb_file.sync_data().await
        };

        thumb_fut
            .await
            .map_err(warp::reject::custom)?;

        let mut thumbnail_buffer = Vec::new();

        thumbnail
            .write_to(&mut thumbnail_buffer, image::ImageOutputFormat::JPEG(200))
            .map_err(warp::reject::custom)?;
    }
    
    Ok(thumbnail_buffer)
}

#[tokio::main]
async fn main() {
    let _ = pretty_env_logger::try_init();

    let hi = warp::get2().and(warp::path("hi").map(|| "Hello, World!"));

    let multipart = warp::post2().and(warp::path("upload").and(
        warp::multipart::form()
            .and_then(handle_multipart)
            .map(|uuids| warp::reply::json(&uuids))
    ));

    let get = warp::get2().and(warp::path("images")
    .and(warp::path::param())
    .and(warp::query())
    .and_then(handle_get_images)
    .map(|buffer| {
        warp::http::Response::builder()
            .header("Content-Type", "image/jpg")
            .body(buffer)
        }));

    let routes = hi.or(get).or(multipart).or(warp::fs::dir("res"));

    warp::serve(routes).run(([127, 0, 0, 1], 3030)).await;
}
