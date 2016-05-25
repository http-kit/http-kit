# How to Contribute to http-kit

Interested in making http-kit better? Radical. Let's talk about how you can get
the most mileage out of it.

## Be Respectful

http-kit is an active project maintained by a small group of contributors who
work on this in their spare time. Please keep this in mind when filing issues -
although a bug you've encountered may be frustrating, the people who will read
and respond to your issue are human beings contributing unpaid time and effort
to the project. Please try to use a polite and respectful tone when filing
issues and reporting bugs.

## Filing Issues

When reporting a bug, it's helpful for us if you include the following
information:
 - Your JDK version
 - Your Leiningen version
 - Which version of http-kit you're using
 - A full stacktrace, if you have one available

## Filing Pull Requests

When filing a pull request, please make sure you've run the test suite on your
local machine and that the tests pass (unfortunately, we've had some issues with
the test suite in CI due to AWS EC2's lack of proper support for IPv6).

Over time, some of http-kit's codebase has become a little messy. We welcome PRs
that look to clean up the existing codebase for readability and maintenance.

If your pull request introduces a new feature or otherwise changes existing
logic, please make sure to add a corresponding test so that we don't experience
a logical regression in the future.
