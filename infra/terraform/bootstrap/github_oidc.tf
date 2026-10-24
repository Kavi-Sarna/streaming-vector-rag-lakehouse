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

  # The demo composition's resources today are the VPC, the Glue schema
  # registry, the producer ECR repository, MSK Serverless, the EKS
  # cluster, and the Grafana network load balancer. Later modules extend
  # this policy when they add the lakehouse bucket.
  statement {
    sid       = "Network"
    effect    = "Allow"
    actions   = ["ec2:*"]
    resources = ["*"]
  }

  statement {
    sid       = "EKS"
    effect    = "Allow"
    actions   = ["eks:*"]
    resources = ["*"]
  }

  statement {
    sid    = "EKSIam"
    effect = "Allow"
    actions = [
      "iam:CreateRole",
      "iam:DeleteRole",
      "iam:GetRole",
      "iam:ListRolePolicies",
      "iam:GetRolePolicy",
      "iam:PutRolePolicy",
      "iam:DeleteRolePolicy",
      "iam:ListAttachedRolePolicies",
      "iam:ListInstanceProfilesForRole",
      "iam:TagRole",
      "iam:UntagRole",
      "iam:UpdateAssumeRolePolicy",
      "iam:AttachRolePolicy",
      "iam:DetachRolePolicy",
      "iam:PassRole",
      "iam:CreateOpenIDConnectProvider",
      "iam:DeleteOpenIDConnectProvider",
      "iam:GetOpenIDConnectProvider",
      "iam:ListOpenIDConnectProviders",
      "iam:TagOpenIDConnectProvider",
      "iam:UntagOpenIDConnectProvider",
      "iam:UpdateOpenIDConnectProviderThumbprint",
      "iam:CreateServiceLinkedRole",
      "iam:GetPolicy",
      "iam:GetPolicyVersion",
    ]
    resources = ["*"]
  }

  statement {
    sid    = "ECR"
    effect = "Allow"
    actions = [
      "ecr:GetAuthorizationToken",
      "ecr:CreateRepository",
      "ecr:DeleteRepository",
      "ecr:DescribeRepositories",
      "ecr:DescribeImages",
      "ecr:ListTagsForResource",
      "ecr:TagResource",
      "ecr:UntagResource",
      "ecr:PutImage",
      "ecr:BatchCheckLayerAvailability",
      "ecr:BatchGetImage",
      "ecr:CompleteLayerUpload",
      "ecr:InitiateLayerUpload",
      "ecr:UploadLayerPart",
      "ecr:GetDownloadUrlForLayer",
      "ecr:PutLifecyclePolicy",
      "ecr:GetLifecyclePolicy",
      "ecr:DeleteLifecyclePolicy",
    ]
    resources = ["*"]
  }

  statement {
    sid    = "MSK"
    effect = "Allow"
    actions = [
      "kafka:CreateClusterV2",
      "kafka:DeleteCluster",
      "kafka:DescribeCluster",
      "kafka:DescribeClusterV2",
      "kafka:GetBootstrapBrokers",
      "kafka:ListClustersV2",
      "kafka:ListTagsForResource",
      "kafka:TagResource",
      "kafka:UntagResource",
    ]
    resources = ["*"]
  }

  statement {
    sid    = "GrafanaLoadBalancer"
    effect = "Allow"
    actions = [
      "elasticloadbalancing:CreateLoadBalancer",
      "elasticloadbalancing:DeleteLoadBalancer",
      "elasticloadbalancing:DescribeLoadBalancers",
      "elasticloadbalancing:DescribeLoadBalancerAttributes",
      "elasticloadbalancing:ModifyLoadBalancerAttributes",
      "elasticloadbalancing:SetSecurityGroups",
      "elasticloadbalancing:SetSubnets",
      "elasticloadbalancing:CreateTargetGroup",
      "elasticloadbalancing:DeleteTargetGroup",
      "elasticloadbalancing:DescribeTargetGroups",
      "elasticloadbalancing:DescribeTargetGroupAttributes",
      "elasticloadbalancing:ModifyTargetGroup",
      "elasticloadbalancing:ModifyTargetGroupAttributes",
      "elasticloadbalancing:CreateListener",
      "elasticloadbalancing:DeleteListener",
      "elasticloadbalancing:DescribeListeners",
      "elasticloadbalancing:DescribeListenerAttributes",
      "elasticloadbalancing:ModifyListener",
      "elasticloadbalancing:ModifyListenerAttributes",
      "elasticloadbalancing:AddTags",
      "elasticloadbalancing:RemoveTags",
      "elasticloadbalancing:DescribeTags",
      "elasticloadbalancing:RegisterTargets",
      "elasticloadbalancing:DeregisterTargets",
      "elasticloadbalancing:DescribeTargetHealth",
    ]
    resources = ["*"]
  }

  statement {
    sid    = "GrafanaNodeTargets"
    effect = "Allow"
    actions = [
      "autoscaling:AttachLoadBalancerTargetGroups",
      "autoscaling:DetachLoadBalancerTargetGroups",
      "autoscaling:DescribeAutoScalingGroups",
      "autoscaling:DescribeLoadBalancerTargetGroups",
    ]
    resources = ["*"]
  }

  statement {
    sid    = "SchemaRegistry"
    effect = "Allow"
    actions = [
      "glue:CreateRegistry",
      "glue:GetRegistry",
      "glue:UpdateRegistry",
      "glue:DeleteRegistry",
      "glue:CreateSchema",
      "glue:GetSchema",
      "glue:UpdateSchema",
      "glue:DeleteSchema",
      "glue:RegisterSchemaVersion",
      "glue:GetSchemaVersion",
      "glue:GetSchemaByDefinition",
      "glue:ListSchemas",
      "glue:ListSchemaVersions",
      "glue:TagResource",
      "glue:UntagResource",
      "glue:GetTags",
    ]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "github_deploy" {
  name   = "rag-demo-github-deploy"
  role   = aws_iam_role.github_deploy.id
  policy = data.aws_iam_policy_document.github_deploy.json
}
