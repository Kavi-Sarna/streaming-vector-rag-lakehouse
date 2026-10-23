data "tls_certificate" "github" {
  url = "https://token.actions.githubusercontent.com"
}

resource "aws_iam_openid_connect_provider" "github" {
  url             = "https://token.actions.githubusercontent.com"
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = [data.tls_certificate.github.certificates[0].sha1_fingerprint]
}

data "aws_iam_policy_document" "github_trust" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    # Repositories created after 2026-07-15 send owner and repository ids
    # in the subject. pull_request lets a same-repo PR read and plan.
    # The demo environment is the approval gate on apply.
    # main is the ref used when a workflow runs from the default branch.
    condition {
      test     = "StringLike"
      variable = "token.actions.githubusercontent.com:sub"
      values = [
        "repo:${local.github_subject_repo}:pull_request",
        "repo:${local.github_subject_repo}:environment:demo",
        "repo:${local.github_subject_repo}:ref:refs/heads/main",
      ]
    }
  }
}

resource "aws_iam_role" "github_deploy" {
  name               = "rag-demo-github-deploy"
  assume_role_policy = data.aws_iam_policy_document.github_trust.json
}

data "aws_iam_policy_document" "github_deploy" {
  statement {
    sid    = "RemoteState"
    effect = "Allow"

    actions = [
      "s3:ListBucket",
      "s3:GetBucketVersioning",
      "s3:GetEncryptionConfiguration",
    ]

    resources = [aws_s3_bucket.state.arn]
  }

  statement {
    sid    = "RemoteStateObjects"
    effect = "Allow"

    actions = [
      "s3:GetObject",
      "s3:PutObject",
      "s3:DeleteObject",
    ]

    resources = ["${aws_s3_bucket.state.arn}/*"]
  }

  # The demo composition's only resources today are the VPC. Later modules
  # extend this policy when they add EKS, MSK, and the lakehouse bucket.
  statement {
    sid       = "Network"
    effect    = "Allow"
    actions   = ["ec2:*"]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "github_deploy" {
  name   = "rag-demo-github-deploy"
  role   = aws_iam_role.github_deploy.id
  policy = data.aws_iam_policy_document.github_deploy.json
}
