package producer

import (
	"testing"

	"github.com/twmb/franz-go/pkg/kerr"
)

func TestTopicAlreadyExists(t *testing.T) {
	if !topicAlreadyExists(kerr.TopicAlreadyExists) {
		t.Fatal("expected an existing topic to be treated as success")
	}
	if topicAlreadyExists(kerr.TopicAuthorizationFailed) {
		t.Fatal("expected an authorization failure to stay an error")
	}
}
