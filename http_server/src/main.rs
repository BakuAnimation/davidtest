use std::path::Path;
use tokio_executor::blocking;
use tokio::fs::{create_dir_all, File, rename};
use tokio::prelude::*;
use warp::Filter;
use uuid::Uuid;
use serde::Deserialize;
use serde_json::{Value, from_str, to_string};
use std::str;

#[derive(Deserialize)]
struct Size {
    width: u32,
    height: u32
}

async fn handle_multipart(project_id: String, mut form: warp::multipart::FormData) -> Result<Vec<Uuid>, warp::Rejection> {
    let img_directory = Path::new("upload_files");

    let mut uuids = Vec::new();

    while let Some(part) = form.next().await {
        let part = part.map_err(warp::reject::custom)?;
        
        let uuid = Uuid::new_v4();
        let filename = format!("{}.jpg", uuid.to_string());
        let image_path = Path::join(img_directory, Path::join(Path::new(&project_id), Path::new(&filename)));

        let image_buffer = part.concat().await;

        create_dir_all(image_path.parent().unwrap()).await.map_err(warp::reject::custom)?;
        let mut image_file = File::create(image_path)
            .await
            .map_err(warp::reject::custom)?;
        image_file.write_all(&image_buffer).await.map_err(warp::reject::custom)?;
        image_file.sync_data().await.map_err(warp::reject::custom)?;

        uuids.push(uuid);
    }

    Ok(uuids)
}


async fn handle_get_images(project_id: String, filename: String, r : Size) -> Result<Vec<u8>, warp::Rejection> {
    let thumbs_directory = Path::new("upload_thumbs");
    let thumb_filename = format!("{}-{}x{}", filename, r.width, r.height);
    let thumbnail_path = Path::join(thumbs_directory, Path::join(Path::new(&project_id), Path::new(&thumb_filename)));

    let mut thumbnail_buffer = Vec::new();

    if thumbnail_path.exists() {
        let mut f = File::open(&thumbnail_path).await.map_err(warp::reject::custom)?;
        f.read_to_end(&mut thumbnail_buffer).await.map_err(warp::reject::custom)?;
    } else {
        let img_directory = Path::new("upload_files");
        let image_path = Path::join(img_directory, Path::join(Path::new(&project_id), Path::new(&filename)));
        let image = image::open(&image_path).map_err(warp::reject::custom)?;

        println!("Resizing {}", filename);
        let thumbnail = blocking::run(move || {
            image.resize(r.width, r.height, image::imageops::FilterType::Lanczos3)
        })
        .await;
        println!("Done {}", filename);

        thumbnail
            .write_to(&mut thumbnail_buffer, image::ImageOutputFormat::JPEG(200))
            .map_err(warp::reject::custom)?;

        let uuid = Uuid::new_v4();
        let temp_filename = format!("{}.jpg", uuid.to_string());
        let temp_path = Path::join(thumbs_directory, Path::new(&temp_filename));

        println!("Writing to {}", temp_filename);
        create_dir_all(temp_path.parent().unwrap()).await.map_err(warp::reject::custom)?;
        let mut thumb_file = File::create(&temp_path)
            .await
            .map_err(warp::reject::custom)?;
        thumb_file.write_all(&thumbnail_buffer).await.map_err(warp::reject::custom)?;
        thumb_file.sync_data().await.map_err(warp::reject::custom)?;
        println!("Renaming to {}", thumb_filename);
        create_dir_all(thumbnail_path.parent().unwrap()).await.map_err(warp::reject::custom)?;
        rename(temp_path, thumbnail_path).await.unwrap();
        println!("Done {}", thumb_filename);

        let mut thumbnail_buffer = Vec::new();

        thumbnail
            .write_to(&mut thumbnail_buffer, image::ImageOutputFormat::JPEG(200))
            .map_err(warp::reject::custom)?;
    }
    
    Ok(thumbnail_buffer)
}

async fn handle_stack(project_id: String, body: String) -> Result<(), warp::Rejection> {
    let stack_directory = Path::new("stacks");
    let stack_path = Path::join(stack_directory, Path::new(&format!("{}.stack", project_id)));
    create_dir_all(stack_path.parent().unwrap()).await.map_err(warp::reject::custom)?;

    let mut json: Vec<String>;

    if stack_path.exists() {
        let mut buffer = Vec::new();
        let mut f = File::open(&stack_path).await.map_err(warp::reject::custom)?;
        f.read_to_end(&mut buffer).await.map_err(warp::reject::custom)?;

        let s = match str::from_utf8(&buffer) {
            Ok(v) => v,
            Err(e) => panic!("Invalid UTF-8 sequence: {}", e),
        };

        println!("{}: Stacking {}", project_id, body);

        json = from_str(s).map_err(warp::reject::custom)?;
    } else {
        json = Vec::new();
    }
    json.push(body);

    let json_as_string = serde_json::to_string(&json).unwrap();

    //TODO concurrency
    let mut written_file = File::create(&stack_path).await.map_err(warp::reject::custom)?;
    written_file.write_all(&json_as_string.as_bytes()).await.map_err(warp::reject::custom)?;
    written_file.sync_data().await.map_err(warp::reject::custom)?;

    Ok(())
}

async fn handle_history(project_id: String) -> Result<Vec<String>, warp::Rejection> {
    let stack_directory = Path::new("stacks");
    let stack_path = Path::join(stack_directory, Path::new(&format!("{}.stack", project_id)));

    if stack_path.exists() {
        let mut buffer = Vec::new();
        let mut f = File::open(&stack_path).await.map_err(warp::reject::custom)?;
        f.read_to_end(&mut buffer).await.map_err(warp::reject::custom)?;

        let s = match str::from_utf8(&buffer) {
            Ok(v) => v,
            Err(e) => panic!("Invalid UTF-8 sequence: {}", e),
        };

        Ok(from_str(s).map_err(warp::reject::custom)?)
    } else {
        Ok(Vec::new())
    }
}

#[tokio::main]
async fn main() {
    let _ = pretty_env_logger::try_init();

    let hi = warp::get2().and(warp::path("hi").map(|| "Hello, World!"));

    let multipart = warp::post2()
    .and(warp::path::param())
    .and(warp::path("upload"))
    .and(warp::multipart::form())
    .and_then(handle_multipart)
    .map(|uuids| warp::reply::json(&uuids));

    let get = warp::get2()
    .and(warp::path::param())
    .and(warp::path("images"))
    .and(warp::path::param())
    .and(warp::query())
    .and_then(handle_get_images)
    .map(|buffer| {
        warp::http::Response::builder()
            .header("Content-Type", "image/jpg")
            .body(buffer)
        });

    let stack = warp::post2()
        .and(warp::path::param())
        .and(warp::path("stack"))
        .and(warp::body::json())
        .and_then(handle_stack)
        .map(|_| "Stacked");
    let history = warp::get2()
        .and(warp::path::param())
        .and_then(handle_history)
        .map(|history| warp::reply::json(&history));

    let routes = hi.or(stack).or(history).or(get).or(multipart).or(warp::fs::dir("res"));

    warp::serve(routes).run(([127, 0, 0, 1], 3030)).await;
}
