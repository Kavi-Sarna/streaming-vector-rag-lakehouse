resource "aws_security_group" "this" {
  name        = "${var.name}-msk"
  description = "MSK Serverless brokers. Clients in the EKS node security group connect on 9098."
  vpc_id      = var.vpc_id
}

resource "aws_vpc_security_group_ingress_rule" "iam" {
  security_group_id            = aws_security_group.this.id
  description                  = "IAM Kafka from the EKS nodes"
  ip_protocol                  = "tcp"
  from_port                    = 9098
  to_port                      = 9098
  referenced_security_group_id = var.client_security_group_id
}

resource "aws_msk_serverless_cluster" "this" {
  cluster_name = var.name

  vpc_config {
    subnet_ids         = var.subnet_ids
    security_group_ids = [aws_security_group.this.id]
  }

  client_authentication {
    sasl {
      iam {
        enabled = true
      }
    }
  }
}

locals {
  topic_arn = "${replace(aws_msk_serverless_cluster.this.arn, ":cluster/", ":topic/")}/${var.topic}"
}

data "aws_iam_policy_document" "producer_trust" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [var.oidc_provider_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "${var.oidc_issuer}:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "${var.oidc_issuer}:sub"
      values   = ["system:serviceaccount:${var.producer_namespace}:${var.producer_service_account}"]
    }
  }
}

data "aws_iam_policy_document" "producer" {
  statement {
    sid    = "Connect"
    effect = "Allow"
    actions = [
      "kafka-cluster:Connect",
      "kafka-cluster:DescribeCluster",
      "kafka-cluster:CreateTopic",
      "kafka-cluster:WriteDataIdempotently",
    ]
    resources = [aws_msk_serverless_cluster.this.arn]
  }

  statement {
    sid    = "Topic"
    effect = "Allow"
    actions = [
      "kafka-cluster:CreateTopic",
      "kafka-cluster:DescribeTopic",
      "kafka-cluster:WriteData",
    ]
    resources = [local.topic_arn]
  }
}

resource "aws_iam_role" "producer" {
  name               = "${var.name}-producer"
  assume_role_policy = data.aws_iam_policy_document.producer_trust.json
}

resource "aws_iam_role_policy" "producer" {
  name   = "${var.name}-producer"
  role   = aws_iam_role.producer.id
  policy = data.aws_iam_policy_document.producer.json
}
