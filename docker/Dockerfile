FROM ubuntu
MAINTAINER Charles Chan <rascov@gmail.com>

# Install dependencies
RUN echo oracle-java8-installer shared/accepted-oracle-license-v1-1 select true | debconf-set-selections && \
    apt-get update && \
    apt-get install -y software-properties-common && \
    add-apt-repository -y ppa:webupd8team/java && \
    apt-get update && \
    apt-get install -y git-core && \
    apt-get install -y python-minimal python-pip python-dev && \
    apt-get install -y oracle-java8-installer ant && \
    apt-get install -y nodejs npm && \
    rm -rf /var/lib/apt/lists/* && \
    rm -rf /var/cache/oracle-jdk8-installer
WORKDIR /usr/local/bin
RUN ln -s nodejs node

# Setup environment variable
ENV HOME /root
ENV JAVA_HOME /usr/lib/jvm/java-8-oracle

# Fetch and install
WORKDIR $HOME
RUN git clone https://github.com/dlinknctu/omniui.git omniui
WORKDIR $HOME/omniui
RUN ./run.sh install

# Expose ports
# 6633 - OpenFlow
# 8000 - GUI
# 5567 - Core REST
EXPOSE 6633 8000 5567

# Define default command
WORKDIR $HOME
CMD ["bash"]