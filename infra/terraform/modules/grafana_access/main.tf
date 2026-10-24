# Client IP preservation stays off so the nodes accept only this load balancer.
# The security group has no client rule. make open-grafana adds one public
# /32, and a later apply leaves that rule in place. Do not add an inline
# ingress block on this group: Terraform would remove the script's rule.
resource "aws_security_group" "this" {
  name        = "${var.name}-grafana"
  description = "Grafana load balancer. make open-grafana adds the one allowed client address."
  vpc_id      = var.vpc_id

  tags = {
    Name = "${var.name}-grafana"
  }
}

resource "aws_vpc_security_group_egress_rule" "nodes" {
  security_group_id            = aws_security_group.this.id
  description                  = "Grafana node port on the EKS nodes"
  ip_protocol                  = "tcp"
  from_port                    = var.node_port
  to_port                      = var.node_port
  referenced_security_group_id = var.node_security_group_id
}

resource "aws_vpc_security_group_ingress_rule" "from_lb" {
  security_group_id            = var.node_security_group_id
  description                  = "Grafana node port from the load balancer"
  ip_protocol                  = "tcp"
  from_port                    = var.node_port
  to_port                      = var.node_port
  referenced_security_group_id = aws_security_group.this.id
}

resource "aws_lb" "this" {
  name               = "${var.name}-grafana"
  load_balancer_type = "network"
  internal           = false
  subnets            = var.public_subnet_ids
  security_groups    = [aws_security_group.this.id]

  enable_cross_zone_load_balancing = true
  enable_deletion_protection       = false

  tags = {
    Name = "${var.name}-grafana"
  }
}

resource "aws_lb_target_group" "nodes" {
  name        = "${var.name}-grafana-nodes"
  port        = var.node_port
  protocol    = "TCP"
  vpc_id      = var.vpc_id
  target_type = "instance"

  preserve_client_ip   = "false"
  deregistration_delay = 0

  health_check {
    enabled             = true
    protocol            = "TCP"
    port                = "traffic-port"
    healthy_threshold   = 2
    unhealthy_threshold = 2
    interval            = 30
    timeout             = 10
  }
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.this.arn
  port              = var.listener_port
  protocol          = "TCP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.nodes.arn
  }
}

resource "aws_autoscaling_attachment" "nodes" {
  autoscaling_group_name = var.autoscaling_group_name
  lb_target_group_arn    = aws_lb_target_group.nodes.arn
}
