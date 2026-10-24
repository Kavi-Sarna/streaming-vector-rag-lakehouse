package mockembed

import (
	"encoding/json"
	"net/http"
)

type embedRequest struct {
	Text string `json:"text"`
}

type embedResponse struct {
	Dimensions int       `json:"dimensions"`
	Vector     []float32 `json:"vector"`
}

// Handler serves GET /health and POST /embed.
func Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
	})
	mux.HandleFunc("POST /embed", embed)
	return mux
}

func embed(w http.ResponseWriter, r *http.Request) {
	var req embedRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid json", http.StatusBadRequest)
		return
	}
	vector := Embed(req.Text)
	writeJSON(w, http.StatusOK, embedResponse{Dimensions: len(vector), Vector: vector})
}

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}
