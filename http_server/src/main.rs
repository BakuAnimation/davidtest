use futures::stream::{ StreamExt, TryStreamExt };
use futures::future::{ FutureExt };
use warp::Filter;

#[tokio::main]
async fn main() {
    let _ = pretty_env_logger::try_init();


    let hi = warp::path("hi").map(|| "Hello, World!");
   // let res = warp::path("").and(warp::fs::dir("res"));
    let multipart = warp::path("upload").and(warp::multipart::form());
    /*
    .and_then(|form: warp::multipart::FormData| {
        async {
            // Collect the fields into (name, value): (String, Vec<u8>)
            let part: Result<Vec<(String, Vec<u8>)>, warp::Rejection> = form
                .and_then(|part| {
                    let name = part.name().to_string();
                    part.concat().map(move |value| Ok((name, value)))
                })
                .try_collect()
                .await
                .map_err(|e| {
                    panic!("multipart error: {:?}", e);
                });
            part
        }
    }));
    */

    let routes = warp::get2().and(hi).or(multipart).or(warp::fs::dir("res"));//;

    warp::serve(routes).run(([127, 0, 0, 1], 3030)).await;
}
