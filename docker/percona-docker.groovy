pipeline {
    environment {
        specName = 'Docker'
    }
    agent {
        label 'min-buster-x64'
    }
    parameters {
        string(
            defaultValue: 'master',
            description: 'Tag/Branch for percona-docker repository',
            name: 'GIT_BRANCH'
        )
        string(
            defaultValue: 'https://github.com/percona/percona-docker.git',
            description: 'percona-docker repository',
            name: 'GIT_REPO'
        )
        choice(
            choices: 'percona-server\npercona-server-mongodb\npercona-distribution-postgresql\npercona-xtrabackup\npercona-toolkit\nproxysql\nhaproxy',
            description: 'Select docker for build',
            name: 'DOCKER_NAME'
        )
        string(
            description: 'Version of selected product',
            name: 'DOCKER_VERSION'
        )
        string(
            description: 'Directory of the product',
            name: 'DOCKER_DIRECTORY'
        )
        choice(
            choices: 'Dockerfile\nDockerfile.debug\nDockerfile.k8s',
            description: 'Extension of dockerfile to be built',
            name: 'DOCKER_FILE'
        )
        choice(
            choices: 'percona\nperconalab',
            description: "Organization push to",
            name: 'DOCKER_ORG'
        )
        booleanParam(
            defaultValue: false,
            description: "Set true to set MAJOR tag to version selected",
            name: 'DOCKER_MAJOR_TAG'
        )
        booleanParam(
            defaultValue: false,
            description: "Triggers save of built image",
            name: 'DOCKER_EXPORT'
        )
        booleanParam(
            defaultValue: false,
            description: "Triggers push of built image to dockerhub",
            name: 'DOCKER_RELEASE'
        )      
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }

    stages {
        stage('Prepare') {
            steps {
                sh '''
                  rm -f docker_builder.sh
                  wget https://raw.githubusercontent.com/Sudokamikaze/jenkins-pipelines/ENG-879/docker/docker_builder.sh
                  chmod +x docker_builder.sh
                  sudo rm -rf tmp
                  mkdir tmp
                  sudo bash -x docker_builder.sh --builddir=$(pwd)/tmp --install_deps=1
                  bash -x docker_builder.sh --builddir=$(pwd)/tmp --repo=${GIT_REPO} --branch=${GIT_BRANCH} --get_sources=1
                '''
            }
        }

        stage('Build Image') {
            steps {
                sh '''
                    sudo bash -x docker_builder.sh --builddir=$(pwd)/tmp --build_container=1 --product=${DOCKER_NAME} \
                        --productdir=${DOCKER_DIRECTORY} --organization=${DOCKER_ORG} --version=${DOCKER_VERSION} --dockerfile=${DOCKER_FILE} \
                        --update_major=${DOCKER_MAJOR_TAG}
                '''
            }
        }
        
        stage('Save Image') {
            when {
                expression { params.DOCKER_EXPORT == true }
            }
            steps {
                sh '''
                    sudo bash -x docker_builder.sh --builddir=$(pwd)/tmp --export_container=1 --product=${DOCKER_NAME} --organization=${DOCKER_ORG} --version=${DOCKER_VERSION}
                    sudo chmod -R 777 ${WORKSPACE}/tarball
                '''
                archiveArtifacts artifacts: 'tarball/**', followSymlinks: false, onlyIfSuccessful: true
            }
        }

        stage('Push Image') {
            when {
                expression { params.DOCKER_RELEASE == true }
            }
            steps {
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh '''
                        sudo docker login -u ${USER} -p ${PASS}
                        sudo bash -x docker_builder.sh --builddir=$(pwd)/tmp --release_container=1 --product=${DOCKER_NAME} --organization=${DOCKER_ORG} --version=${DOCKER_VERSION} \
                         --update_major=${DOCKER_MAJOR_TAG}
                    '''
                }
            }
        }

        stage('Cleanup') {
            steps {
                sh '''
                    sudo bash -x docker_builder.sh --builddir=$(pwd)/tmp --cleanup=1
                    sudo rm -rf ${WORKSPACE}/*
                '''
            }            
        }
    }

    post {
        always {
            deleteDir()
        }
    }
}
