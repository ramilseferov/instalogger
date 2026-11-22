use axum::{
    extract::{
        ws::{Message, WebSocket, WebSocketUpgrade},
        State,
    },
    response::IntoResponse,
};
use std::sync::Arc;
use tracing::info;

use crate::api::AppState;

pub async fn ws_handler(
    ws: WebSocketUpgrade,
    State(state): State<Arc<AppState>>,
) -> impl IntoResponse {
    ws.on_upgrade(move |socket| handle_socket(socket, state))
}

async fn handle_socket(mut socket: WebSocket, state: Arc<AppState>) {
    info!("New WebSocket client connected");
    let mut rx = state.scheduler.events_tx.subscribe();

    while let Ok(msg_text) = rx.recv().await {
        if socket.send(Message::Text(msg_text.into())).await.is_err() {
            break;
        }
    }
}
